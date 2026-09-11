package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.dataflow.DataFlowFacts;
import jp.cobolinsight.core.dataflow.ProgramDataFlow;
import jp.cobolinsight.core.dataflow.ValueInterval;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * R028 Computing a negative value into an unsigned item. Detects, via interval value analysis,
 * places where a SUBTRACT or COMPUTE result may become negative for a numeric receiving item whose
 * PICTURE has no sign (S). Since an unsigned item holds no sign, a negative result is stored as its
 * absolute value, which corrupts subsequent comparisons and aggregations. The check is whether the
 * receiving item's interval, at the arithmetic statement's outflow (the entry of the following
 * node), may include a negative value. Whether the receiving item is signed is resolved by the
 * shared resolver.
 */
public final class UnsignedNegativeResultRule implements Rule {

    private static final Set<String> ARITHMETIC_VERBS = Set.of("SUBTRACT", "COMPUTE");

    private static final RuleMeta META =
            RuleMeta.named("R028", "符号なし項目への負の結果の格納", "データ移動")
                    .summary("PICTURE に S を持たない項目に、"
                            + "負になり得る演算結果を格納する箇所を検出します。")
                    .rationale("符号なし項目は符号を保持しないため、"
                            + "負の結果が絶対値として格納され、以後の比較と集計が誤ります。")
                    .detection("SUBTRACT・COMPUTE の受け取り側項目のうち、"
                            + "算術文の直後の値が負になり得るもので、"
                            + "PICTURE に S がないものを検出します。")
                    .remedy("受け取り側項目の PICTURE に S を付けてください。"
                            + "負にならない前提なら、その条件を演算の前に検査してください。")
                    .example("""
                            01  WS-DIFF  PIC 9(5).
                                COMPUTE WS-DIFF = WS-A - WS-B.
                            """, """
                            01  WS-DIFF  PIC S9(5).
                                COMPUTE WS-DIFF = WS-A - WS-B.
                            """)
                    .severity(Severity.MEDIUM)
                    .commands(Command.LINT)
                    .targets(AssetKind.COBOL)
                    .needs(Needs.CFG, Needs.DATAFLOW, Needs.SOURCE_TEXT)
                    .build();

    @Override
    public RuleMeta meta() {
        return META;
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
                evaluate(model, cfg, df, DataFlowSupport.of(model, texts), findings);
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
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        receiver + "（符号なし）に " + simple.verb().toUpperCase(Locale.ROOT)
                                + " の結果が負で格納され得ます。符号が失われ、絶対値になります。",
                        new SourcePosition(model.sourceFile(), simple.range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    /** Whether the receiving item's interval, at the arithmetic statement's outflow (the entry of the following node), may include a negative value. */
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
