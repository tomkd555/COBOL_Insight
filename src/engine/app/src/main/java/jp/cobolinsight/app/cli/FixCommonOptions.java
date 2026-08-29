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
 * `fix preview`・`fix apply` の子コマンドが共有する入力・解析設定のオプション群(picocli の
 * {@code @Mixin} として取り込む)。オプションは lint/translate と整合させる。
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

    /** 既定解決を適用したコピー句探索パス。 */
    List<Path> resolvedCopybookPaths() {
        return CommonScanOptions.resolveCopybookPaths(inputDir, copybookPaths);
    }

    FixRunner.Options toRunnerOptions(RuleSet ruleSet) {
        return new FixRunner.Options(inputDir, resolvedCopybookPaths(), codepageOverrides,
                ruleSet);
    }
}
