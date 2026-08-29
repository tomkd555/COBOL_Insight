package jp.cobolinsight.app.cli;

import jp.cobolinsight.rules.RuleSet;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The set of input and analysis-configuration options shared by the `fix preview` and `fix apply`
 * subcommands (pulled in via picocli's {@code @Mixin}). The options are kept consistent with
 * lint/translate.
 */
final class FixCommonOptions {

    @Parameters(index = "0", paramLabel = "INPUT_DIR", description = "資産フォルダ")
    Path inputDir;

    @Option(names = "--copybook-path", paramLabel = "DIR",
            description = "コピー句探索パス(既定: 走査で見つかったコピー句の置き場所)")
    List<Path> copybookPaths = new ArrayList<>();

    @Option(names = "--codepage", paramLabel = "FILE=CHARSET",
            description = "ファイル単位のコードページ手動指定(相対パスまたはファイル名=コードページ)。自動判別に優先する")
    Map<String, String> codepageOverrides = new LinkedHashMap<>();

    /** The copybook search path with default resolution applied. */
    List<Path> resolvedCopybookPaths() {
        return CommonScanOptions.resolveCopybookPaths(inputDir, copybookPaths);
    }

    FixRunner.Options toRunnerOptions(RuleSet ruleSet) {
        return new FixRunner.Options(inputDir, resolvedCopybookPaths(), codepageOverrides,
                ruleSet);
    }
}
