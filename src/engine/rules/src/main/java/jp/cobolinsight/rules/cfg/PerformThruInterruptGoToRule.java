package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.GoToStatement;
import jp.cobolinsight.core.semantic.PerformRelation;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.ProcedureMap;
import jp.cobolinsight.core.semantic.ProcedureNames;
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
 * R007 A GO TO that interrupts a PERFORM THRU range. Among the group of paragraphs forming a
 * contiguous execution range under PERFORM ... THRU, detects a GO TO that branches directly,
 * from a paragraph outside the range, into a paragraph inside the range without going through
 * the range's entry paragraph. This skips the initialization the entry paragraph is responsible
 * for and jumps in partway through, producing a runtime inconsistency.
 */
public final class PerformThruInterruptGoToRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R007", "PERFORM THRU の入口を経由しない GO TO 文", "制御フロー")
            .summary("PERFORM THRU の範囲に、入口の段落を経由せず"
                    + "範囲外から GO TO 文で入る箇所を検出します。")
            .rationale("入口の段落が担う初期化を飛ばして途中から実行するため、"
                    + "初期化前の値のまま処理が進みます。")
            .detection("PERFORM ... THRU の範囲に含まれる段落のうち入口の段落以外に、"
                    + "範囲外の段落から GO TO 文で制御を移すものを検出します。"
                    + "範囲の内側の段落にある GO TO 文と、入口の段落へ移る GO TO 文は対象外です。")
            .remedy("範囲の入口の段落から入るか、"
                    + "飛び込み先の処理を別の段落に分けて PERFORM 文で呼び出してください。")
            .example("""
                    GO TO CALC-STEP2.
                    CALC-START.
                    CALC-STEP2.
                    CALC-EXIT.
                        EXIT.
                    """, """
                    PERFORM CALC-START THRU CALC-EXIT.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            evaluate(model, findings);
        }
        return findings;
    }

    private void evaluate(CobolSemanticModel model, List<Finding> findings) {
        List<Procedure> procedures = model.procedures();
        List<GoToStatement> gotos = new ArrayList<>();
        for (Procedure procedure : procedures) {
            CfgSupport.walk(procedure.statements(), statement -> {
                if (statement instanceof GoToStatement goTo) {
                    gotos.add(goTo);
                }
            });
        }
        // Names resolve through ProcedureNames, from the section of the statement that writes them.
        ProcedureNames names = new ProcedureNames(procedures);
        ProcedureMap procedureMap = new ProcedureMap(model);
        for (GoToStatement goTo : gotos) {
            int holderIndex = procedureMap.procedureOf(goTo);
            if (holderIndex < 0) {
                continue;
            }
            Procedure holder = procedures.get(holderIndex);
            Optional<String> holderSection = ProcedureNames.sectionOf(holder);
            for (PerformRelation perform : model.performs()) {
                if (perform.thruProcedure().isEmpty()) {
                    continue;
                }
                ProcedureNames.Range range =
                        names.rangeOf(perform, procedureMap.sectionOf(perform)).orElse(null);
                if (range == null) {
                    continue;
                }
                int targetIndex = range.from();
                int thruIndex = range.to();
                if (holderIndex >= targetIndex && holderIndex <= thruIndex) {
                    continue; // The paragraph containing the GO TO is inside the range -> not an interruption
                }
                String hit = null;
                for (int t = 0; t < goTo.targets().size(); t++) {
                    int target = names.indexOf(goTo.targets().get(t),
                            goTo.targetSections().get(t), holderSection);
                    if (target > targetIndex && target <= thruIndex) {
                        hit = goTo.targets().get(t);
                        break;
                    }
                }
                if (hit != null) {
                    findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                            hit + " に GO TO で直接入っています。PERFORM "
                                    + perform.targetProcedure() + " THRU "
                                    + perform.thruProcedure().get() + " の範囲外の段落 "
                                    + holder.name() + " からで、入口を経由しません。",
                            new SourcePosition(goTo.range().start().file(),
                                    goTo.range().start().line(), 1,
                                    SourcePosition.UNKNOWN_BYTE_OFFSET)));
                    break; // One finding per GO TO
                }
            }
        }
    }
}
