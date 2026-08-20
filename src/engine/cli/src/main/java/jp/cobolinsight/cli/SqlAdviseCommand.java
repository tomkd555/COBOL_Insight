package jp.cobolinsight.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * `sql-lint` サブコマンド。資産フォルダの埋め込みSQLを解析し、指摘(S001〜S006)を
 * SARIF 2.1.0ファイルへ書き出して処理サマリをJSONで標準出力へ書く。終了コードは検出結果で
 * 分岐する(成功=0・警告あり=1・エラー=2)。バグ検出は lint サブコマンドが担う。
 */
@Command(name = "sql-lint", mixinStandardHelpOptions = true,
        description = "資産フォルダの埋め込みSQLを解析し、指摘をSARIFへ出力する")
public final class SqlAdviseCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "INPUT_DIR", description = "資産フォルダ")
    Path inputDir;

    @Option(names = "--sarif", paramLabel = "FILE", defaultValue = "cobol-insight-sql.sarif",
            description = "指摘の一覧を書き出すファイル(SARIF 2.1.0形式)(既定: ${DEFAULT-VALUE})")
    Path sarifFile;

    @Option(names = "--copybook-path", paramLabel = "DIR",
            description = "コピー句探索パス(既定: 走査で見つかったコピー句の置き場所)")
    List<Path> copybookPaths = new ArrayList<>();

    @Option(names = "--codepage", paramLabel = "FILE=CHARSET",
            description = "ファイル単位のコードページ手動指定(相対パスまたはファイル名=コードページ)。自動判別に優先する")
    Map<String, String> codepageOverrides = new LinkedHashMap<>();

    @Option(names = "--rule-config", paramLabel = "FILE",
            description = "ルールの有効・無効を書いた設定ファイル(JSON)")
    Path ruleConfigFile;

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        List<Path> searchPaths = CommonScanOptions.resolveCopybookPaths(inputDir, copybookPaths);
        Set<String> disabled = RuleConfig.resolveDisabled(spec, ruleConfigFile);
        SqlAdviseRunner.Result result = SqlAdviseRunner.run(
                new SqlAdviseRunner.Options(inputDir, searchPaths, codepageOverrides, disabled));
        try {
            Files.writeString(sarifFile, result.sarifJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println(result.summaryJson(sarifFile.toString()));
        return result.exitCode();
    }
}
