package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.pipeline.ExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * `rules`. Displays the list and descriptions of built-in and user-defined rules. The only
 * subcommand that does not take an asset folder, and it performs no analysis.
 */
@Command(name = "rules", mixinStandardHelpOptions = true,
        description = "検出ルールの一覧と説明を表示する")
public final class RulesCommand implements Callable<Integer> {

    @Mixin
    RuleOptions ruleOptions;

    @Option(names = "--json", description = "JSONで出力する(GUIが読む形式)")
    boolean json;

    @Option(names = "--id", paramLabel = "RULE_ID",
            description = "指定したIDのルールだけを、説明の全文つきで表示する")
    String ruleId;

    @Override
    public Integer call() {
        // Configuration errors are recorded as ruleErrors on the listing itself, so nothing is written to standard error here.
        RulesRunner.Result result =
                RulesRunner.run(new RulesRunner.Options(ruleOptions.ruleSet(), ruleId));
        if (result.detail() && result.rules().isEmpty()) {
            System.err.println("該当するルールがありません: " + ruleId + "。--id を外すと一覧を出します。");
            return ExitCodes.ERRORS;
        }
        System.out.print(json ? result.toJson() + System.lineSeparator() : result.toText());
        return ExitCodes.SUCCESS;
    }
}
