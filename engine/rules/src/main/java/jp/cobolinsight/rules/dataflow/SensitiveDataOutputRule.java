package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraphs;
import jp.cobolinsight.engineapi.dataflow.DataFlowFacts;
import jp.cobolinsight.engineapi.dataflow.ProgramDataFlow;
import jp.cobolinsight.engineapi.dataflow.TaintKind;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * R027 機密データ項目のマスキングなし出力。名称末尾が -SSN / -ACCT-NO / -CARD-NO の機密項目(および
 * それを伝播した変数)が、マスキング・暗号化を経ずに DISPLAY・帳票出力(WRITE)・ログ出力(CALL)へ
 * 渡る箇所を汚染追跡で検出する。リテラル代入などで値を差し替えた変数は汚染が絶たれるため対象外になる。
 */
public final class SensitiveDataOutputRule implements Rule {

    private static final Set<String> OUTPUT_VERBS = Set.of("DISPLAY", "WRITE", "CALL");

    @Override
    public String id() {
        return "R027";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.MEDIUM;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.DATA_FLOW;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        DataFlowFacts facts = context.artifact(DataFlowFacts.class).orElse(null);
        ControlFlowGraphs cfgs = context.artifact(ControlFlowGraphs.class).orElse(null);
        if (facts == null || cfgs == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            ProgramDataFlow df = facts.of(model).orElse(null);
            ControlFlowGraph cfg = cfgs.of(model).orElse(null);
            if (df != null && cfg != null) {
                evaluate(model, cfg, df, findings);
            }
        }
        return findings;
    }

    private void evaluate(CobolSemanticModel model, ControlFlowGraph cfg, ProgramDataFlow df,
            List<Finding> findings) {
        for (CfgNode node : cfg.nodes()) {
            Statement statement = node.statement().orElse(null);
            if (!(statement instanceof SimpleStatement simple)
                    || !OUTPUT_VERBS.contains(simple.verb().toUpperCase(Locale.ROOT))) {
                continue;
            }
            Set<String> sensitive = df.taintedAt(node, TaintKind.SENSITIVE);
            if (sensitive.isEmpty()) {
                continue;
            }
            Set<String> exposed = new LinkedHashSet<>(df.usesAt(node));
            exposed.retainAll(sensitive);
            if (!exposed.isEmpty()) {
                SourcePosition position = new SourcePosition(model.sourceFile(),
                        simple.range().start().line(), 1, SourcePosition.UNKNOWN_BYTE_OFFSET);
                findings.add(new Finding(id(), defaultSeverity().toLevel(),
                        "機密項目 " + String.join(", ", exposed)
                                + " をマスキング・暗号化せずに出力している。機密情報が露出する。",
                        position,
                        TaintCodeFlows.of(model, df, node, TaintKind.SENSITIVE, exposed, position,
                                "出力する"),
                        List.of()));
            }
        }
    }
}
