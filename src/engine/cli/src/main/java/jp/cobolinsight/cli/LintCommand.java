package jp.cobolinsight.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * `lint` サブコマンド。資産フォルダを構文段階のルールで静的解析し、SARIF 2.1.0ファイルを
 * 書き出して処理サマリをJSONで標準出力へ書く。終了コードは検出結果で分岐する
 * (成功=0・警告あり=1・エラー=2)。
 */
@Command(name = "lint", mixinStandardHelpOptions = true,
        description = "資産フォルダをルールで静的解析し、SARIFを出力する")
public final class LintCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "INPUT_DIR", description = "資産フォルダ")
    Path inputDir;

    @Option(names = "--sarif", paramLabel = "FILE", defaultValue = "cobol-insight.sarif",
            description = "指摘の一覧を書き出すファイル(SARIF 2.1.0形式)(既定: ${DEFAULT-VALUE})")
    Path sarifFile;

    @Option(names = "--copybook-path", paramLabel = "DIR",
            description = "コピー句探索パス(既定: 走査で見つかったコピー句の置き場所)")
    List<Path> copybookPaths = new ArrayList<>();

    @Option(names = "--codepage", paramLabel = "FILE=CHARSET",
            description = "ファイル単位のコードページ手動指定(相対パスまたはファイル名=コードページ)。自動判別に優先する")
    Map<String, String> codepageOverrides = new LinkedHashMap<>();

    @Option(names = "--disable-rule", paramLabel = "RULE_ID",
            description = "無効化するルールID(繰り返し指定可)")
    List<String> disabledRules = new ArrayList<>();

    @Option(names = "--user-rules", paramLabel = "FILE",
            description = "利用者定義ルールの定義ファイル(JSON)。無い場合は組み込みルールだけを実行する")
    Path userRulesFile;

    @Override
    public Integer call() {
        List<Path> searchPaths = CommonScanOptions.resolveCopybookPaths(inputDir, copybookPaths);
        Set<String> disabled = new LinkedHashSet<>(disabledRules);
        LintRunner.Result result = LintRunner.run(
                new LintRunner.Options(inputDir, searchPaths, codepageOverrides, disabled,
                        userRulesFile));
        try {
            Files.writeString(sarifFile, result.sarifJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println(result.summaryJson(sarifFile.toString()));
        return result.exitCode();
    }
}
