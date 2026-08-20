package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.GoToStatement;
import jp.cobolinsight.engineapi.semantic.Procedure;
import jp.cobolinsight.engineapi.semantic.ProcedureKind;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;

import java.util.ArrayList;
import java.util.List;

/**
 * R014 節(SECTION)の流下。節の末尾が EXIT・終端(STOP/GOBACK/EXIT PROGRAM)・無条件 GO TO で
 * 終わらず、次の節へ物理的に流下する構成を検出する。PERFORM で呼ぶ設計の節が、意図せず次節へ
 * 流れ込む誤りを捉える。
 */
public final class SectionFallThroughRule implements Rule {

    /** 節単位(節ヘッダ手続きと、その配下の段落群を定義順に保持)。 */
    private record SectionUnit(String name, List<Procedure> members) {
    }

    @Override
    public String id() {
        return "R014";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("セクション末尾のEXIT文欠如によるフォールスルー", "制御フロー")
                .summary("末尾が EXIT・終了文・無条件 GO TO のいずれでもなく、"
                        + "次の節へ流れ落ちる節を検出します。")
                .rationale("PERFORM で呼ぶ設計の節が、直接実行されたときに次の節まで続けて"
                        + "実行され、二重処理や順序の狂いを生みます。")
                .detection("節の末尾が EXIT・STOP・GOBACK・EXIT PROGRAM・無条件 GO TO の"
                        + "いずれでもないものを検出します。")
                .remedy("節の末尾に EXIT 段落を置き、そこで処理を閉じます。")
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
                .build();
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.MEDIUM;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.CONTROL_FLOW;
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
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                        "節 " + units.get(i).name() + " は末尾が EXIT・終端・GO TO で終わらず、"
                                + "次の節 " + units.get(i + 1).name() + " へ流下する。",
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
                current = null; // どの節にも属さない段落は節の連なりを断つ
            }
        }
        return units;
    }

    /** 節の最後の実行文(文を持つ最後の手続きの、末尾のトップレベル文)。 */
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
     * 制御がこの文で節の外へ出るか。GO TO は、飛び先が1つで DEPENDING ON を持たないものだけを
     * 無条件分岐とみなす。GO TO ... DEPENDING ON は添字の値によって分岐せず流下し得るため除く。
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
