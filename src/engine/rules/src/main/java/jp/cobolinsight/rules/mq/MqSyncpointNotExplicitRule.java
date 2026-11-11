package jp.cobolinsight.rules.mq;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.cobol.ServiceCalls;

import java.util.ArrayList;
import java.util.List;

/**
 * R094 An MQGET or MQPUT that leaves the syncpoint option unsaid. On z/OS a call that names
 * neither option runs inside the unit of work; on every other platform it runs outside. The
 * program's behaviour on a failure — whether the message it read comes back, whether the message
 * it put is withdrawn — is therefore decided by where it happens to run, and a program moved or
 * copied between platforms changes its recovery without changing a line.
 */
public final class MqSyncpointNotExplicitRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R094", "同期点オプションを明示しない MQGET・MQPUT", RuleCategory.INTEGRITY)
            .technology(Technology.MQ)
            .symptom("障害時のメッセージの消失・二重処理")
            .summary("MQGET を持ちながら MQGMO の同期点オプションを、MQPUT・MQPUT1 を持ちながら"
                    + "MQPMO の同期点オプションを、どこにも指定しないプログラムを検出します。")
            .rationale("同期点オプションを省略したときの扱いは z/OS と他のプラットフォームで"
                    + "逆です。障害時に読んだメッセージが戻るか、送ったメッセージが取り消されるかが"
                    + "実行環境で決まり、設計した回復動作になりません。")
            .detection("MQGET を CALL するプログラムが MQGMO-SYNCPOINT・MQGMO-NO-SYNCPOINT・"
                    + "MQGMO-SYNCPOINT-IF-PERSISTENT のいずれも参照しない場合と、MQPUT・MQPUT1 を"
                    + "CALL するプログラムが MQPMO-SYNCPOINT・MQPMO-NO-SYNCPOINT のいずれも"
                    + "参照しない場合を検出します。報告は最初の呼び出しに 1 件ずつです。")
            .remedy("作業単位に含めるなら SYNCPOINT、含めないなら NO-SYNCPOINT を"
                    + "オプションに必ず加えてください。")
            .example("""
                    MOVE MQGMO-WAIT TO MQGMO-OPTIONS.
                    """, """
                    COMPUTE MQGMO-OPTIONS = MQGMO-WAIT + MQGMO-SYNCPOINT.
                    """)
            .severity(Severity.MEDIUM)
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
            List<ServiceCalls.Call> gets = MqSupport.calls(model, "MQGET");
            List<ServiceCalls.Call> puts = MqSupport.calls(model, "MQPUT", "MQPUT1");
            if (gets.isEmpty() && puts.isEmpty()) {
                continue;
            }
            String text = ServiceCalls.programText(model);
            if (!gets.isEmpty() && !text.contains("MQGMO-SYNCPOINT")
                    && !text.contains("MQGMO-NO-SYNCPOINT")) {
                findings.add(MqSupport.finding(META, gets.get(0),
                        "MQGET に同期点オプション（MQGMO-SYNCPOINT・MQGMO-NO-SYNCPOINT）の"
                                + "指定がありません。省略時の扱いは実行環境で変わります。"));
            }
            if (!puts.isEmpty() && !text.contains("MQPMO-SYNCPOINT")
                    && !text.contains("MQPMO-NO-SYNCPOINT")) {
                findings.add(MqSupport.finding(META, puts.get(0),
                        puts.get(0).target() + " に同期点オプション（MQPMO-SYNCPOINT・"
                                + "MQPMO-NO-SYNCPOINT）の指定がありません。"
                                + "省略時の扱いは実行環境で変わります。"));
            }
        }
        return findings;
    }
}
