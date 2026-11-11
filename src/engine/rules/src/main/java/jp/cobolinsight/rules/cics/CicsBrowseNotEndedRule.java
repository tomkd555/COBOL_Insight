package jp.cobolinsight.rules.cics;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * R086 A STARTBR with no ENDBR. A browse holds a position in the file, and with it a string of
 * the VSAM data set and shared buffers, from STARTBR until ENDBR or the end of the task. A task
 * that browses and then goes on to other work, or to a long conversation, keeps them: other tasks
 * wait for a string, and an update of the same control interval by this very task waits for
 * itself.
 */
public final class CicsBrowseNotEndedRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R086", "ENDBR のない STARTBR", RuleCategory.PERFORMANCE)
            .technology(Technology.CICS)
            .symptom("VSAM ストリング待ち・自タスクとのデッドロック")
            .summary("STARTBR で開始した走査を、同じファイルの ENDBR で終了しないプログラムを"
                    + "検出します。")
            .rationale("走査はタスクの終わりまで VSAM のストリングとバッファーを保持します。"
                    + "他のタスクがストリングを待ち、同じタスクが同じ制御インターバルを"
                    + "更新すると自分自身を待ちます。")
            .detection("EXEC CICS STARTBR の FILE（DATASET）ごとに、同じプログラムに同じファイルの"
                    + "ENDBR があるかを確かめ、ないものを検出します。ファイル名は書かれたままの"
                    + "文字列で突き合わせます。報告はファイルにつき最初の STARTBR に 1 件です。")
            .remedy("走査を終えたら、他の処理に移る前に EXEC CICS ENDBR を実行してください。")
            .example("""
                    EXEC CICS STARTBR FILE('ZAIKO') RIDFLD(WS-KEY) END-EXEC.
                    EXEC CICS READNEXT FILE('ZAIKO') INTO(WS-REC) RIDFLD(WS-KEY) END-EXEC.
                    """, """
                    EXEC CICS STARTBR FILE('ZAIKO') RIDFLD(WS-KEY) END-EXEC.
                    EXEC CICS READNEXT FILE('ZAIKO') INTO(WS-REC) RIDFLD(WS-KEY) END-EXEC.
                    EXEC CICS ENDBR FILE('ZAIKO') END-EXEC.
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
            List<CicsCommands.Command> commands = CicsCommands.of(model);
            Set<String> ended = new java.util.HashSet<>();
            for (CicsCommands.Command command : commands) {
                if (command.is("ENDBR")) {
                    command.operand("FILE", "DATASET").ifPresent(ended::add);
                }
            }
            Set<String> reported = new java.util.HashSet<>();
            for (CicsCommands.Command command : commands) {
                String file = command.is("STARTBR")
                        ? command.operand("FILE", "DATASET").orElse(null) : null;
                if (file != null && !ended.contains(file) && reported.add(file)) {
                    findings.add(CicsCommands.finding(META, command, "ファイル " + file
                            + " の STARTBR に対応する ENDBR がありません。"
                            + "走査がタスクの終わりまで VSAM のストリングを保持します。"));
                }
            }
        }
        return findings;
    }
}
