package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Paths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * `lint`。資産フォルダをルールで静的解析し、SARIF 2.1.0ファイルを書き出して処理サマリをJSONで
 * 標準出力へ書く。終了コードは検出結果で分岐する(成功=0・警告あり=1・エラー=2)。
 *
 * <p>{@code --file} を与えると、その1本と、そこから解決するコピー句だけを対象とする。
 */
@Command(name = "lint", mixinStandardHelpOptions = true,
        description = "資産フォルダをルールで静的解析し、SARIFを出力する")
public final class LintCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "INPUT_DIR", description = "資産フォルダ")
    Path inputDir;

    @Option(names = "--file", paramLabel = "FILE",
            description = "この1本だけを解析する(コピー句は探索パスから解決する)")
    Path singleFile;

    @Option(names = "--sarif", paramLabel = "FILE", defaultValue = "cobol-insight.sarif",
            description = "指摘の一覧を書き出すファイル(SARIF 2.1.0形式)(既定: ${DEFAULT-VALUE})")
    Path sarifFile;

    @Option(names = "--copybook-path", paramLabel = "DIR",
            description = "コピー句探索パス(既定: 走査で見つかったコピー句の置き場所)")
    List<Path> copybookPaths = new ArrayList<>();

    @Option(names = "--codepage", paramLabel = "FILE=CHARSET",
            description = "ファイル単位のコードページ手動指定(相対パスまたはファイル名=コードページ)。自動判別に優先する")
    Map<String, String> codepageOverrides = new LinkedHashMap<>();

    @Mixin
    RuleOptions ruleOptions;

    @Override
    public Integer call() {
        List<Path> searchPaths = CommonScanOptions.resolveCopybookPaths(inputDir, copybookPaths);
        LintRunner.Result result = LintRunner.run(new LintRunner.Options(inputDir, searchPaths,
                codepageOverrides, ruleOptions.reportingRuleSet(), singleFile));
        Paths.writeString(sarifFile, result.sarifJson());
        System.out.println(result.summaryJson(sarifFile.toString()));
        return result.exitCode();
    }
}
