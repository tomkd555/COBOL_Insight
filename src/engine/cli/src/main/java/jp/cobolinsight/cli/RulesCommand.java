package jp.cobolinsight.cli;

import jp.cobolinsight.engineapi.pipeline.ExitCodes;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * `rules` サブコマンド。組み込みルールと利用者定義ルールの一覧・説明を表示する。
 * 資産フォルダを取らない唯一のサブコマンドであり、解析は行わない。
 */
@Command(name = "rules", mixinStandardHelpOptions = true,
        description = "検出ルールの一覧と説明を表示する")
public final class RulesCommand implements Callable<Integer> {

    @Option(names = "--user-rules", paramLabel = "FILE",
            description = "利用者定義ルールの定義ファイル(JSON)。一覧へ併せて載せる")
    Path userRulesFile;

    @Option(names = "--rule-config", paramLabel = "FILE",
            description = "ルールの有効・無効を書いた設定ファイル(JSON)。各ルールの有効・無効へ反映する")
    Path ruleConfigFile;

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = "--json", description = "JSONで出力する(GUIが読む形式)")
    boolean json;

    @Option(names = "--id", paramLabel = "RULE_ID",
            description = "指定したIDのルールだけを、説明の全文つきで表示する")
    String ruleId;

    @Override
    public Integer call() {
        RulesRunner.Result result = RulesRunner.run(new RulesRunner.Options(userRulesFile, ruleId,
                RuleConfig.resolveDisabled(spec, ruleConfigFile)));
        if (result.detail() && result.rules().isEmpty()) {
            System.err.println("該当するルールが無い: " + ruleId);
            return ExitCodes.ERRORS;
        }
        System.out.print(json ? result.toJson() + System.lineSeparator() : result.toText());
        return ExitCodes.SUCCESS;
    }
}
