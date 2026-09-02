package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.GoToStatement;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.ProcedureKind;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.List;

/**
 * R014 Fall-through of a SECTION. Detects a construct where a section's end does not terminate
 * with EXIT, a terminal statement (STOP/GOBACK/EXIT PROGRAM), or an unconditional GO TO, and
 * instead physically falls through into the next section. Catches the error of a section
 * designed to be called via PERFORM unintentionally flowing into the next section.
 */
public final class SectionFallThroughRule implements Rule {

    /** A section unit (the section-header procedure plus its subordinate paragraphs, kept in definition order). */
    private record SectionUnit(String name, List<Procedure> members) {
    }

    private static final RuleMeta META = RuleMeta
            .named("R014", "節末尾のEXIT文欠如", "制御フロー")
            .summary("末尾が EXIT 文・終了文・無条件の GO TO 文のいずれでもなく、"
                    + "次の節へ制御が移る節を検出する。")
            .rationale("PERFORM 文で呼び出す設計の節が、直接実行されたときに次の節まで続けて"
                    + "実行され、二重処理や順序の狂いを生む。")
            .detection("節の末尾が EXIT・STOP・GOBACK・EXIT PROGRAM・無条件の GO TO の"
                    + "いずれでもないものを検出する。")
            .remedy("節の末尾に EXIT 段落を置き、そこで処理を閉じる。")
            .example("""
                    CALC-SEC SECTION.
                        COMPUTE WS-TAX = WS-AMT * 0.10.
                    NEXT-SEC SECTION.
                    """, """
                    CALC-SEC SECTION.
                        COMPUTE WS-TAX = WS-AMT * 0.10.
                    CALC-EXIT.
                        EXIT.
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT, Command.REPORT)
            .targets(AssetKind.COBOL)
            .needs(Needs.SEMANTIC)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            List<SectionUnit> units = sectionUnits(model);
            for (int i = 0; i < units.size() - 1; i++) {
                Statement last = lastExecutable(units.get(i));
                if (last == null || isTerminating(last)) {
                    continue;
                }
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "節 " + units.get(i).name() + " は末尾が EXIT・終了文・GO TO で終わらず、"
                                + "次の節 " + units.get(i + 1).name() + " へ制御が移る。",
                        new SourcePosition(model.sourceFile(), last.range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
        return findings;
    }

    private static List<SectionUnit> sectionUnits(CobolSemanticModel model) {
        List<SectionUnit> units = new ArrayList<>();
        List<Procedure> current = null;
        for (Procedure procedure : model.procedures()) {
            if (procedure.kind() == ProcedureKind.SECTION) {
                current = new ArrayList<>();
                current.add(procedure);
                units.add(new SectionUnit(procedure.name(), current));
            } else if (current != null && procedure.sectionName().isPresent()) {
                current.add(procedure);
            } else {
                current = null; // A paragraph belonging to no section breaks the run of sections
            }
        }
        return units;
    }

    /** The section's last executable statement (the last top-level statement of the last procedure that has any statements). */
    private static Statement lastExecutable(SectionUnit unit) {
        for (int i = unit.members().size() - 1; i >= 0; i--) {
            List<Statement> statements = unit.members().get(i).statements();
            if (!statements.isEmpty()) {
                return statements.get(statements.size() - 1);
            }
        }
        return null;
    }

    /**
     * Whether control leaves the section at this statement. For GO TO, only one with a single
     * target and no DEPENDING ON is treated as an unconditional branch. GO TO ... DEPENDING ON is
     * excluded because, depending on the subscript's value, it may not branch and can fall
     * through instead.
     */
    private static boolean isTerminating(Statement statement) {
        if (statement instanceof SimpleStatement simple) {
            String verb = CfgSupport.upper(simple.verb());
            return verb.equals("STOP") || verb.equals("GOBACK") || verb.equals("EXIT");
        }
        return statement instanceof GoToStatement goTo
                && goTo.targets().size() == 1 && goTo.dependingOn().isEmpty();
    }
}
