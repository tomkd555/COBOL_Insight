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
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.AnalysisPhase;
import jp.cobolinsight.core.spi.Rule;
import jp.cobolinsight.core.spi.RuleDoc;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jp.cobolinsight.rules.dataflow.DataFlowSupport.Section;
import jp.cobolinsight.rules.dataflow.DataFlowSupport.TableRef;

/**
 * R005 OCCURS 範囲外の添字・指標。OCCURS 表への添字参照ノードで、添字の取り得る値域(区間値域解析)が
 * 表の上限を超え得る、または 0 以下になり得る箇所を検出する。範囲外の添字は表に隣接する記憶域を
 * 読み書きする。上限は表項目または OCCURS を持つ直近の上位項目から解決する。LINKAGE 節の表は
 * 呼出元が保証する領域のため対象外とする。
 */
public final class OccursSubscriptRangeRule implements Rule {

    @Override
    public String id() {
        return "R005";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("添字・指標のOCCURS範囲外アクセス", "添字・指標")
                .summary("添字の取り得る値が表の上限を超える、"
                        + "または 0 以下になり得る参照を検出します。")
                .rationale("表の外の記憶域を読み書きするため、"
                        + "隣接する項目を壊すか、実行時に領域違反で異常終了します。")
                .detection("区間値域解析で添字の値域を求め、OCCURS の上限を超え得る、"
                        + "または 0 以下になり得るものを検出します。上限は表項目、または OCCURS を"
                        + "持つ直近の上位項目から解決します。LINKAGE 節の表は呼出元が領域を"
                        + "保証するため対象外とします。")
                .remedy("添字の値域を参照前に検査します。表の大きさが足りないなら OCCURS を見直します。")
                .example("""
                        01  WS-TBL.
                            05  WS-ITEM  PIC X(10) OCCURS 10 TIMES.
                            PERFORM VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 20
                                MOVE SPACE TO WS-ITEM(WS-I)
                            END-PERFORM.
                        """, """
                        01  WS-TBL.
                            05  WS-ITEM  PIC X(10) OCCURS 10 TIMES.
                            PERFORM VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 10
                                MOVE SPACE TO WS-ITEM(WS-I)
                            END-PERFORM.
                        """)
                .build();
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.HIGH;
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
        Set<String> reported = new LinkedHashSet<>();
        for (CfgNode node : cfg.nodes()) {
            Statement statement = node.statement().orElse(null);
            if (statement == null) {
                continue;
            }
            String text = textOf(statement);
            for (TableRef ref : support.tableRefs(text)) {
                List<Integer> dims = support.occursDims(ref.tableName());
                if (dims.isEmpty() || support.sectionOf(ref.tableName()) == Section.LINKAGE) {
                    continue;
                }
                List<String> subscripts = ref.subscripts();
                for (int i = 0; i < subscripts.size(); i++) {
                    // 添字は外側の次元から並ぶため、同じ順の OCCURS 上限と突き合わせる。次元数を
                    // 超える添字は最も内側の上限で見る。
                    int max = dims.get(Math.min(i, dims.size() - 1));
                    ValueInterval iv = subscriptInterval(df, node, subscripts.get(i));
                    if (iv == null || !(iv.mayExceed(max) || iv.mayBeNonPositive())) {
                        continue;
                    }
                    int line = statement.range().start().line();
                    String key = line + "|" + DataFlowSupport.norm(ref.tableName());
                    if (reported.add(key)) {
                        findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                                "表 " + ref.tableName() + " の添字が OCCURS 上限 " + max
                                        + " を超え得る、または 0 以下になり得る。範囲外参照になる。",
                                new SourcePosition(model.sourceFile(), line, 1,
                                        SourcePosition.UNKNOWN_BYTE_OFFSET)));
                    }
                    break;
                }
            }
        }
    }

    private static ValueInterval subscriptInterval(ProgramDataFlow df, CfgNode node, String sub) {
        String token = sub.trim();
        if (token.matches("[+-]?\\d+")) {
            try {
                return ValueInterval.point(Long.parseLong(token));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return df.intervalAt(node, token.toUpperCase(Locale.ROOT)).orElse(null);
    }

    private static String textOf(Statement statement) {
        if (statement instanceof SimpleStatement simple) {
            return simple.text();
        }
        if (statement instanceof CompoundStatement compound) {
            return compound.conditionText();
        }
        return "";
    }
}
