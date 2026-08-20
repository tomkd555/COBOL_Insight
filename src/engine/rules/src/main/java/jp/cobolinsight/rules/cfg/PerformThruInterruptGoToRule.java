package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.GoToStatement;
import jp.cobolinsight.engineapi.semantic.PerformRelation;
import jp.cobolinsight.engineapi.semantic.Procedure;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R007 PERFORM THRU の範囲へ割り込む GO TO。PERFORM ... THRU で一連の実行範囲となる段落群の
 * うち、範囲の入口段落を経由せず範囲内部の段落へ、範囲外の段落から GO TO で直接分岐する箇所を
 * 検出する。範囲入口の初期化を飛ばして途中へ入り込むため、実行時の不整合を生む。
 */
public final class PerformThruInterruptGoToRule implements Rule {

    @Override
    public String id() {
        return "R007";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("PERFORM THRUの範囲不整合", "制御フロー")
                .summary("PERFORM THRU の実行範囲へ、入口段落を経由せず"
                        + "外から GO TO で直接飛び込む箇所を検出します。")
                .rationale("入口段落が担う初期化を飛ばして途中から実行されるため、"
                        + "初期化前の値のまま処理が進みます。")
                .detection("PERFORM ... THRU の範囲に含まれる段落のうち、入口段落以外へ、"
                        + "範囲外の段落から GO TO で分岐するものを検出します。")
                .remedy("範囲の入口段落から入るようにするか、"
                        + "飛び込み先の処理を別の段落へ分けて PERFORM で呼びます。")
                .example("""
                        GO TO CALC-STEP2.
                        CALC-START.
                        CALC-STEP2.
                        CALC-EXIT.
                            EXIT.
                        """, """
                        PERFORM CALC-START THRU CALC-EXIT.
                        """)
                .build();
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.HIGH;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.CONTROL_FLOW;
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
        for (GoToStatement goTo : gotos) {
            Procedure holder = CfgSupport.containingProcedure(model, goTo);
            if (holder == null) {
                continue;
            }
            String holderName = CfgSupport.upper(holder.name());
            for (PerformRelation perform : model.performs()) {
                if (perform.thruProcedure().isEmpty()) {
                    continue;
                }
                int targetIndex = indexOf(procedures, perform.targetProcedure());
                int thruIndex = indexOf(procedures, perform.thruProcedure().get());
                if (targetIndex < 0 || thruIndex < 0 || thruIndex < targetIndex) {
                    continue;
                }
                Set<String> range = new LinkedHashSet<>();
                for (int i = targetIndex; i <= thruIndex; i++) {
                    range.add(CfgSupport.upper(procedures.get(i).name()));
                }
                if (range.contains(holderName)) {
                    continue; // GO TO を含む段落が範囲内 → 割り込みではない
                }
                String entry = CfgSupport.upper(perform.targetProcedure());
                String hit = null;
                for (String rawTarget : goTo.targets()) {
                    String target = CfgSupport.upper(rawTarget);
                    if (range.contains(target) && !target.equals(entry)) {
                        hit = rawTarget;
                        break;
                    }
                }
                if (hit != null) {
                    findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                            "PERFORM " + perform.targetProcedure() + " THRU "
                                    + perform.thruProcedure().get()
                                    + " の実行範囲へ、範囲外の段落 " + holder.name()
                                    + " から GO TO " + hit + " で入口を経由せず割り込んでいる。",
                            new SourcePosition(model.sourceFile(),
                                    goTo.range().start().line(), 1,
                                    SourcePosition.UNKNOWN_BYTE_OFFSET)));
                    break; // 1つの GO TO につき1件
                }
            }
        }
    }

    private static int indexOf(List<Procedure> procedures, String name) {
        String wanted = CfgSupport.upper(name);
        for (int i = 0; i < procedures.size(); i++) {
            if (CfgSupport.upper(procedures.get(i).name()).equals(wanted)) {
                return i;
            }
        }
        return -1;
    }
}
