package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraphs;
import jp.cobolinsight.engineapi.dataflow.DataFlowFacts;
import jp.cobolinsight.engineapi.dataflow.ProgramDataFlow;
import jp.cobolinsight.engineapi.dataflow.ValueInterval;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * R028 符号なし項目への負値算出。PICTURE に符号(S)を持たない数字受信項目に対し、SUBTRACT または
 * COMPUTE の演算結果が負になり得る箇所を区間値域解析で検出する。符号なし項目は符号を保持しないため、
 * 負の結果は絶対値として格納され、以後の比較・集計が誤る。判定は算術文の流出(後続ノード入口)の
 * 受信項目区間が負を含み得るかで行う。受信項目の符号有無は共有リゾルバで解決する。
 */
public final class UnsignedNegativeResultRule implements Rule {

    private static final Set<String> ARITHMETIC_VERBS = Set.of("SUBTRACT", "COMPUTE");

    @Override
    public String id() {
        return "R028";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("符号なし前提の数値項目への負値算出", "データ移動")
                .summary("PICTURE に S を持たない項目へ、"
                        + "負になり得る演算結果を格納する箇所を検出する。")
                .rationale("符号なし項目は符号を保持しないため、"
                        + "負の結果が絶対値として格納され、以後の比較と集計が誤る。")
                .detection("SUBTRACT・COMPUTE の受信項目のうち、算術文の後続位置での区間値域が"
                        + "負を含み得るもので、受信項目の PICTURE に S が無いものを検出する。")
                .remedy("受信項目の PICTURE へ S を付ける。"
                        + "負にならない前提なら、その条件を演算前に検査する。")
                .example("""
                        01  WS-DIFF  PIC 9(5).
                            COMPUTE WS-DIFF = WS-A - WS-B.
                        """, """
                        01  WS-DIFF  PIC S9(5).
                            COMPUTE WS-DIFF = WS-A - WS-B.
                        """)
                .build();
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
        SourceTextIndex texts = context.artifact(SourceTextIndex.class).orElse(null);
        if (facts == null || cfgs == null || texts == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            ProgramDataFlow df = facts.of(model).orElse(null);
            ControlFlowGraph cfg = cfgs.of(model).orElse(null);
            if (df != null && cfg != null) {
                evaluate(model, cfg, df, new DataFlowSupport(model, texts), findings);
            }
        }
        return findings;
    }

    private void evaluate(CobolSemanticModel model, ControlFlowGraph cfg, ProgramDataFlow df,
            DataFlowSupport support, List<Finding> findings) {
        for (CfgNode node : cfg.nodes()) {
            Statement statement = node.statement().orElse(null);
            if (!(statement instanceof SimpleStatement simple)
                    || !ARITHMETIC_VERBS.contains(simple.verb().toUpperCase(Locale.ROOT))) {
                continue;
            }
            for (String receiver : df.defsAt(node)) {
                if (!support.isUnsignedNumeric(receiver) || !resultMayBeNegative(cfg, df, node, receiver)) {
                    continue;
                }
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                        "符号なし項目 " + receiver + " に " + simple.verb().toUpperCase(Locale.ROOT)
                                + " の結果が負になり得る値を格納している。符号が失われ不正値になる。",
                        new SourcePosition(model.sourceFile(), simple.range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    /** 算術文の流出(後続ノード入口)で受信項目区間が負を含み得るか。 */
    private static boolean resultMayBeNegative(ControlFlowGraph cfg, ProgramDataFlow df, CfgNode node,
            String receiver) {
        String name = receiver.toUpperCase(Locale.ROOT);
        for (CfgNode succ : cfg.successors(node)) {
            ValueInterval iv = df.intervalAt(succ, name).orElse(null);
            if (iv != null && iv.mayBeNegative()) {
                return true;
            }
        }
        return false;
    }
}
