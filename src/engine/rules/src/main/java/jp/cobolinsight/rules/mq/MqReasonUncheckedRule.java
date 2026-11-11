package jp.cobolinsight.rules.mq;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.cfg.CfgSupport;
import jp.cobolinsight.rules.cobol.ServiceCalls;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R092 An MQI call whose completion code and reason code nobody looks at. Every MQI call returns
 * normally and says what happened in its last two parameters: no message available, queue full,
 * queue manager quiescing, a truncated message. A program that does not test them processes a
 * buffer the MQGET never filled, or believes a message was put that was not.
 */
public final class MqReasonUncheckedRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R092", "MQI 呼び出しの完了コード・理由コード未検査", RuleCategory.INTEGRITY)
            .technology(Technology.MQ)
            .symptom("メッセージなし（2033）・キュー満杯（2053）などの見逃し")
            .summary("MQGET・MQPUT・MQOPEN などの MQI 呼び出しの後、次の MQI 呼び出しまでに"
                    + "完了コードも理由コードも検査しない箇所を検出します。")
            .rationale("MQI は結果をすべて完了コードと理由コードで返し、呼び出し自体は正常に"
                    + "戻ります。検査しないと、受信していないバッファーを処理するか、"
                    + "送信できなかったメッセージを送信済みとして処理が進みます。")
            .detection("MQCONN・MQOPEN・MQGET・MQPUT・MQPUT1・MQCLOSE・MQDISC・MQCMIT・MQBACK などを"
                    + "CALL する文について、USING の最後の 2 つの作用対象を完了コード・"
                    + "理由コードとします。次の MQI 呼び出しに達するまでの前方経路で、"
                    + "そのどちらも（条件名を含めて）条件で参照せず、他の項目へ転記もしない"
                    + "ものを検出します。")
            .remedy("呼び出しの直後に完了コードを MQCC-OK と比べ、それ以外は理由コードで"
                    + "原因を分けて処理してください。")
            .example("""
                    CALL 'MQGET' USING WS-HCONN WS-HOBJ MQMD MQGMO
                         WS-BUFLEN WS-BUFFER WS-DATALEN WS-COMPCODE WS-REASON.
                    MOVE WS-BUFFER TO WS-DENBUN.
                    """, """
                    CALL 'MQGET' USING WS-HCONN WS-HOBJ MQMD MQGMO
                         WS-BUFLEN WS-BUFFER WS-DATALEN WS-COMPCODE WS-REASON.
                    IF WS-COMPCODE NOT = MQCC-OK
                        PERFORM MQ-ERROR
                    END-IF.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .needs(Needs.CFG)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        ControlFlowGraphs cfgs = context.artifact(ControlFlowGraphs.class).orElse(null);
        if (cfgs == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            ControlFlowGraph cfg = cfgs.of(model).orElse(null);
            if (cfg == null) {
                continue;
            }
            for (CfgNode node : cfg.nodes()) {
                ServiceCalls.Call call = MqSupport.mqiCall(model, node).orElse(null);
                if (call == null || call.operands().size() < 2) {
                    continue;
                }
                Set<String> outcome = new LinkedHashSet<>();
                for (int fromEnd = 1; fromEnd <= 2; fromEnd++) {
                    String name = call.fromEnd(fromEnd).orElseThrow();
                    outcome.add(name);
                    CfgSupport.itemNamed(model, name)
                            .ifPresent(item -> outcome.addAll(ServiceCalls.namesOf(item)));
                }
                if (!ServiceCalls.readBeforeNext(cfg, node,
                        other -> MqSupport.mqiCall(model, other).isPresent(), outcome)) {
                    findings.add(MqSupport.finding(META, call, call.target()
                            + " の後で完了コード " + call.fromEnd(2).orElseThrow()
                            + " も理由コード " + call.fromEnd(1).orElseThrow()
                            + " も検査していません。呼び出しの失敗に気づかないまま"
                            + "後続が進みます。"));
                }
            }
        }
        return findings;
    }
}
