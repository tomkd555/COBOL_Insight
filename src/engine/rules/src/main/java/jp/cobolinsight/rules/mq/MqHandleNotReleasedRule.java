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
 * R095 A program that opens a queue and never closes it, or connects and never disconnects. In a
 * batch job the end of the job releases both. Under CICS and in a long-running program the handles
 * stay with the task or the address space, and a program driven for every message leaks one object
 * handle per call until the queue manager refuses the next MQOPEN.
 */
public final class MqHandleNotReleasedRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R095", "MQCLOSE・MQDISC のない MQOPEN・MQCONN", RuleCategory.PERFORMANCE)
            .technology(Technology.MQ)
            .symptom("ハンドルの枯渇（MQRC 2017）")
            .summary("MQOPEN を持ちながら MQCLOSE を、MQCONN を持ちながら MQDISC を"
                    + "持たないプログラムを検出します。")
            .rationale("ハンドルがタスクやアドレス空間に残ります。メッセージごとに起動される"
                    + "プログラムでは呼び出しのたびにハンドルが増え、やがて MQOPEN が"
                    + "失敗します。")
            .detection("MQOPEN を CALL し MQCLOSE を CALL しないプログラムと、MQCONN・MQCONNX を"
                    + "CALL し MQDISC を CALL しないプログラムを検出します。"
                    + "解放を別のプログラムに任せる構成は読み取れないため、その構成の"
                    + "プログラムも検出されます。報告は最初の MQOPEN・MQCONN に 1 件ずつです。")
            .remedy("使い終えたキューは MQCLOSE で閉じ、MQCONN した接続は終了前に"
                    + "MQDISC で切断してください。")
            .severity(Severity.LOW)
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
            List<ServiceCalls.Call> opens = MqSupport.calls(model, "MQOPEN");
            if (!opens.isEmpty() && MqSupport.calls(model, "MQCLOSE").isEmpty()) {
                findings.add(MqSupport.finding(META, opens.get(0),
                        model.programId() + " は MQOPEN したキューを MQCLOSE していません。"
                                + "オブジェクトハンドルが解放されずに残ります。"));
            }
            List<ServiceCalls.Call> connects = MqSupport.calls(model, "MQCONN", "MQCONNX");
            if (!connects.isEmpty() && MqSupport.calls(model, "MQDISC").isEmpty()) {
                findings.add(MqSupport.finding(META, connects.get(0),
                        model.programId() + " は " + connects.get(0).target()
                                + " した接続を MQDISC していません。"
                                + "接続ハンドルが解放されずに残ります。"));
            }
        }
        return findings;
    }
}
