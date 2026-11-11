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
import java.util.List;
import java.util.regex.Pattern;

/**
 * R093 An MQGET in a loop that never clears the message and correlation identifiers. MQGET
 * returns the identifiers of the message it read in the message descriptor, and the next MQGET
 * uses whatever the descriptor holds as its selection: it asks for a second message with the
 * identifiers of the first. There is none, so the second call ends with "no message available"
 * although the queue is full of messages — the program reads one message per run.
 */
public final class MqMessageIdNotResetRule implements Rule {

    private static final Pattern MOVE_TO_ID = Pattern.compile(
            "\\bMOVE\\s+\\S+\\s+TO\\s+(?:\\S+\\s+)*\\S*(MSGID|CORRELID)\\b");

    private static final RuleMeta META = RuleMeta
            .named("R093", "繰り返しの MQGET での MsgId・CorrelId 未初期化", RuleCategory.INTEGRITY)
            .technology(Technology.MQ)
            .symptom("2 件目以降が受信できない（MQRC 2033）")
            .summary("繰り返し実行される MQGET を持ちながら、メッセージ記述子の MsgId・"
                    + "CorrelId を初期化しないプログラムを検出します。")
            .rationale("MQGET は読んだメッセージの識別子を MsgId・CorrelId に返します。"
                    + "初期化せずに次の MQGET を出すと、同じ識別子のメッセージを探すことになり、"
                    + "キューにメッセージが残っていても「メッセージなし」で終わります。")
            .detection("制御フロー上で自身に戻る経路のある MQGET（繰り返しの中の MQGET）を持つ"
                    + "プログラムのうち、MQMI-NONE・MQCI-NONE を参照せず、MSGID・CORRELID で"
                    + "終わる項目への転記もなく、照合を行わない MQMO-NONE の指定もないものを"
                    + "検出します。報告は最初の該当する MQGET に 1 件です。")
            .remedy("MQGET の前に毎回 MQMI-NONE を MsgId へ、MQCI-NONE を CorrelId へ転記するか、"
                    + "MQGMO の MatchOptions に MQMO-NONE を指定してください。")
            .example("""
                    PERFORM UNTIL WS-REASON = MQRC-NO-MSG-AVAILABLE
                        CALL 'MQGET' USING WS-HCONN WS-HOBJ MQMD MQGMO
                             WS-BUFLEN WS-BUFFER WS-DATALEN WS-COMPCODE WS-REASON
                    END-PERFORM.
                    """, """
                    PERFORM UNTIL WS-REASON = MQRC-NO-MSG-AVAILABLE
                        MOVE MQMI-NONE TO MQMD-MSGID
                        MOVE MQCI-NONE TO MQMD-CORRELID
                        CALL 'MQGET' USING WS-HCONN WS-HOBJ MQMD MQGMO
                             WS-BUFLEN WS-BUFFER WS-DATALEN WS-COMPCODE WS-REASON
                    END-PERFORM.
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
            String text = ServiceCalls.programText(model);
            if (ServiceCalls.mentions(text, "MQMI-NONE") || ServiceCalls.mentions(text, "MQCI-NONE")
                    || ServiceCalls.mentions(text, "MQMO-NONE")
                    || MOVE_TO_ID.matcher(text).find()) {
                continue;
            }
            for (CfgNode node : cfg.nodes()) {
                ServiceCalls.Call call = MqSupport.mqiCall(model, node)
                        .filter(c -> c.target().equals("MQGET")).orElse(null);
                if (call != null
                        && CfgSupport.forwardHasMatch(cfg, node, other -> false, n -> n == node)) {
                    findings.add(MqSupport.finding(META, call,
                            "繰り返し実行される MQGET の前に MsgId・CorrelId を"
                                    + "初期化していません。2 件目以降は、最初のメッセージと"
                                    + "同じ識別子を探して「メッセージなし」になります。"));
                    break;
                }
            }
        }
        return findings;
    }
}
