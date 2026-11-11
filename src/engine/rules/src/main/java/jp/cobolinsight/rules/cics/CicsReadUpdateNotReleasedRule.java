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
 * R088 A READ UPDATE with nothing that releases the record. READ UPDATE takes exclusive control of
 * the record — for a non-recoverable VSAM file, of its whole control interval — and keeps it until
 * a REWRITE, a DELETE or an UNLOCK for the same file, or the end of the unit of work. A program
 * that reads for update and then decides not to update holds the record for the rest of the task,
 * and every other transaction that wants it waits.
 */
public final class CicsReadUpdateNotReleasedRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R088", "解放されない READ UPDATE", RuleCategory.PERFORMANCE)
            .technology(Technology.CICS)
            .symptom("レコードの排他待ち")
            .summary("UPDATE を指定した READ を持ちながら、同じファイルの REWRITE・DELETE・"
                    + "UNLOCK を持たないプログラムを検出します。")
            .rationale("READ UPDATE はレコードの排他制御を取得し、REWRITE・DELETE・UNLOCK か"
                    + "作業単位の終わりまで保持します。更新しないまま処理を続けると、"
                    + "そのレコードを使う他のトランザクションが待たされます。")
            .detection("EXEC CICS READ のうち UPDATE を持つものについて、同じプログラムに"
                    + "同じファイルの REWRITE・DELETE・UNLOCK があるかを確かめ、ないものを"
                    + "検出します。ファイル名は書かれたままの文字列で突き合わせます。"
                    + "報告はファイルにつき最初の READ UPDATE に 1 件です。")
            .remedy("更新しない経路では EXEC CICS UNLOCK でレコードを解放してください。"
                    + "参照だけなら UPDATE を外してください。")
            .example("""
                    EXEC CICS READ FILE('ZAIKO') INTO(WS-REC) RIDFLD(WS-KEY) UPDATE END-EXEC.
                    """, """
                    EXEC CICS READ FILE('ZAIKO') INTO(WS-REC) RIDFLD(WS-KEY) UPDATE END-EXEC.
                    EXEC CICS REWRITE FILE('ZAIKO') FROM(WS-REC) END-EXEC.
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
            Set<String> released = new java.util.HashSet<>();
            for (CicsCommands.Command command : commands) {
                if (command.is("REWRITE") || command.is("DELETE") || command.is("UNLOCK")) {
                    command.operand("FILE", "DATASET").ifPresent(released::add);
                }
            }
            Set<String> reported = new java.util.HashSet<>();
            for (CicsCommands.Command command : commands) {
                String file = command.is("READ") && command.has("UPDATE")
                        ? command.operand("FILE", "DATASET").orElse(null) : null;
                if (file != null && !released.contains(file) && reported.add(file)) {
                    findings.add(CicsCommands.finding(META, command, "ファイル " + file
                            + " の READ UPDATE に対応する REWRITE・DELETE・UNLOCK がありません。"
                            + "レコードの排他制御が作業単位の終わりまで残ります。"));
                }
            }
        }
        return findings;
    }
}
