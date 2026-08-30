package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.GoToStatement;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * R009 GO TO that deviates from structured flow. Detects a GO TO that crosses SECTION
 * boundaries: it fires when the section containing the paragraph with the GO TO differs from the
 * section containing the target paragraph. A branch that crosses sections breaks the structure
 * of dividing processing by section and makes the control flow impossible to trace locally.
 * A GO TO within the same section, and an interruption into a PERFORM THRU range (handled by
 * R007), are out of scope.
 */
public final class GoToStructureDeviationRule implements Rule {

    private static final RuleMeta META =
            RuleMeta.named("R009", "GO TO文による構造化フローからの逸脱", "制御フロー")
                    .summary("節(SECTION)をまたぐ GO TO を検出します。")
                    .rationale("節で処理を区切る構成が崩れ、"
                            + "制御の流れを節の内側だけでは追えなくなります。")
                    .detection("GO TO を含む段落の所属節と、飛び先段落の所属節が異なるものを検出します。"
                            + "同一節内の GO TO と、PERFORM THRU 範囲への割り込み(R007 が扱う)は"
                            + "対象外とします。")
                    .remedy("節の外へ出る分岐を、PERFORM の呼び分けか条件分岐へ置き換えます。")
                    .example("""
                            MAIN-SEC SECTION.
                                GO TO ERROR-PARA.
                            ERROR-SEC SECTION.
                            ERROR-PARA.
                            """, """
                            MAIN-SEC SECTION.
                                IF WS-ERROR-FLG = "Y"
                                    PERFORM ERROR-SEC
                                END-IF.
                            """)
                    .severity(Severity.ADVISORY)
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
            List<GoToStatement> gotos = new ArrayList<>();
            for (Procedure procedure : model.procedures()) {
                CfgSupport.walk(procedure.statements(), statement -> {
                    if (statement instanceof GoToStatement goTo) {
                        gotos.add(goTo);
                    }
                });
            }
            for (GoToStatement goTo : gotos) {
                Procedure holder = CfgSupport.containingProcedure(model, goTo);
                if (holder == null || holder.sectionName().isEmpty()) {
                    continue;
                }
                String fromSection = CfgSupport.upper(holder.sectionName().get());
                for (String rawTarget : goTo.targets()) {
                    Optional<String> targetSection = sectionOf(model, rawTarget);
                    if (targetSection.isEmpty()) {
                        continue;
                    }
                    if (!CfgSupport.upper(targetSection.get()).equals(fromSection)) {
                        findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                                "GO TO " + rawTarget + " は所属節 " + holder.sectionName().get()
                                        + " から別の節 " + targetSection.get()
                                        + " へ分岐しており、節の構造から逸脱している。",
                                new SourcePosition(model.sourceFile(),
                                        goTo.range().start().line(), 1,
                                        SourcePosition.UNKNOWN_BYTE_OFFSET)));
                        break;
                    }
                }
            }
        }
        return findings;
    }

    private static Optional<String> sectionOf(CobolSemanticModel model, String paragraphName) {
        String wanted = CfgSupport.upper(paragraphName);
        for (Procedure procedure : model.procedures()) {
            if (CfgSupport.upper(procedure.name()).equals(wanted)) {
                return procedure.sectionName();
            }
        }
        return Optional.empty();
    }
}
