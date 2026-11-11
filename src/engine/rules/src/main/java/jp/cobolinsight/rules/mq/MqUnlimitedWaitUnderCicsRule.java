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
import jp.cobolinsight.rules.cfg.CfgSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * R096 An MQGET that waits without limit in a CICS program. The task stays suspended until a
 * message arrives, holding its storage, its terminal and whatever it has locked; CICS cannot purge
 * it cleanly, and a shutdown waits for it. A wait interval in milliseconds lets the task wake,
 * look at the outcome and decide.
 */
public final class MqUnlimitedWaitUnderCicsRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R096", "CICS プログラムでの無期限の MQGET 待ち", RuleCategory.PERFORMANCE)
            .technology(Technology.MQ)
            .symptom("タスクの滞留・CICS の終了遅延")
            .summary("EXEC CICS を持つプログラムが MQGET を出し、待ち時間に MQWI-UNLIMITED を"
                    + "指定しているものを検出します。")
            .rationale("メッセージが届くまでタスクが中断したまま残り、記憶域・端末・ロックを"
                    + "保持します。CICS の終了もこのタスクを待ちます。")
            .detection("EXEC CICS のコマンドと MQGET の CALL を持つプログラムのうち、"
                    + "手続き部で MQWI-UNLIMITED を参照するものを検出します。"
                    + "報告は MQWI-UNLIMITED を参照する最初の文に 1 件です。")
            .remedy("待ち時間をミリ秒で指定し、MQRC-NO-MSG-AVAILABLE を受けたら処理を"
                    + "終えるか、MQGMO-FAIL-IF-QUIESCING を併せて指定してください。")
            .example("""
                    MOVE MQWI-UNLIMITED TO MQGMO-WAITINTERVAL.
                    """, """
                    MOVE 5000 TO MQGMO-WAITINTERVAL.
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
        Pattern unlimited = CfgSupport.wordPattern("MQWI-UNLIMITED");
        for (CobolSemanticModel model : context.cobolPrograms()) {
            if (model.embeddedBlocks().stream().noneMatch(block -> block.kind().isCics())
                    || MqSupport.calls(model, "MQGET").isEmpty()) {
                continue;
            }
            boolean[] reported = {false};
            model.procedures().forEach(procedure ->
                    CfgSupport.walk(procedure.statements(), statement -> {
                        if (!reported[0] && unlimited.matcher(
                                CfgSupport.referenceText(statement)).find()) {
                            reported[0] = true;
                            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                                    model.programId() + " は CICS のもとで MQGET を無期限に"
                                            + "待ちます。メッセージが届くまでタスクが"
                                            + "中断したまま残ります。",
                                    CfgSupport.at(statement.range())));
                        }
                    }));
        }
        return findings;
    }
}
