package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.dataflow.DataFlowFacts;
import jp.cobolinsight.core.dataflow.ProgramDataFlow;
import jp.cobolinsight.core.dataflow.TaintKind;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * R027 Output of a sensitive data item without masking. Detects, via taint tracking, places where a
 * sensitive item whose name ends in -SSN / -ACCT-NO / -CARD-NO (or a variable that propagated it)
 * reaches a DISPLAY, report output (WRITE), or log output (CALL) without going through masking or
 * encryption. A variable whose value was replaced by, say, a literal assignment is excluded because
 * the taint is cut off.
 */
public final class SensitiveDataOutputRule implements Rule {

    private static final Set<String> OUTPUT_VERBS = Set.of("DISPLAY", "WRITE", "CALL");

    private static final RuleMeta META =
            RuleMeta.named("R027", "機密データ項目のマスキングなし出力", "セキュリティ")
                    .summary("機密項目の値を、マスキングも暗号化も経ずに"
                            + "表示・帳票・ログへ渡す箇所を検出します。")
                    .rationale("個人番号や口座番号が画面・帳票・ログに残り、"
                            + "それらを閲覧できる範囲がそのまま漏えいの範囲になります。")
                    .detection("名前の末尾が -SSN・-ACCT-NO・-CARD-NO の項目を機密とみなし、その値が"
                            + "DISPLAY・帳票出力（WRITE）・ログ出力（CALL）に到達するものを検出します。"
                            + "定数の転記で値を差し替えた項目は対象外です。")
                    .remedy("出力する前に伏せ字へ置き換えるか、末尾の数けただけを残してください。")
                    .example("""
                            DISPLAY "口座番号: " WS-ACCT-NO.
                            """, """
                            MOVE ALL "*" TO WS-MASKED.
                            MOVE WS-ACCT-NO(13:4) TO WS-MASKED(13:4).
                            DISPLAY "口座番号: " WS-MASKED.
                            """)
                    .severity(Severity.MEDIUM)
                    .commands(Command.LINT, Command.REPORT)
                    .targets(AssetKind.COBOL)
                    .needs(Needs.SEMANTIC, Needs.CFG, Needs.DATAFLOW)
                    .build();

    @Override
    public RuleMeta meta() {
        return META;
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
                findings.add(new Finding(META.id(), META.defaultSeverity().toLevel(),
                        String.join(", ", exposed) + " をマスキングせずに "
                                + simple.verb().toUpperCase(Locale.ROOT)
                                + " へ渡しています。機密情報が露出します。",
                        position,
                        TaintCodeFlows.of(model, df, node, TaintKind.SENSITIVE, exposed, position,
                                "出力する"),
                        List.of()));
            }
        }
    }
}
