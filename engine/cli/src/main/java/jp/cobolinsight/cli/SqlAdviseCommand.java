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
 * `sql-advise` サブコマンド。資産フォルダの埋め込みSQLをSQL助言ルール(S001〜S006)で解析し、
 * SARIF 2.1.0ファイルを書き出して処理サマリをJSONで標準出力へ書く。終了コードは検出結果で
 * 分岐する(成功=0・警告あり=1・エラー=2)。バグ検出は lint サブコマンドが担う(裁定A5・A6)。
 */
@Command(name = "sql-advise", mixinStandardHelpOptions = true,
        description = "資産フォルダの埋め込みSQLをSQL助言ルールで解析し、SARIFを出力する")
public final class SqlAdviseCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "INPUT_DIR", description = "資産フォルダ")
    Path inputDir;

    @Option(names = "--sarif", paramLabel = "FILE", defaultValue = "cobol-insight-sql.sarif",
            description = "SARIF 2.1.0出力ファイル(既定: ${DEFAULT-VALUE})")
    Path sarifFile;

    @Option(names = "--copybook-path", paramLabel = "DIR",
            description = "コピー句探索パス(既定: INPUT_DIR配下のcopybook・copy)")
    List<Path> copybookPaths = new ArrayList<>();

    @Option(names = "--codepage", paramLabel = "FILE=CHARSET",
            description = "ファイル単位のコードページ手動指定(相対パスまたはファイル名=コードページ)。自動判別に優先する")
    Map<String, String> codepageOverrides = new LinkedHashMap<>();

    @Option(names = "--disable-rule", paramLabel = "RULE_ID",
            description = "無効化するルールID(繰り返し指定可)")
    List<String> disabledRules = new ArrayList<>();

    @Override
    public Integer call() {
        List<Path> searchPaths = new ArrayList<>(copybookPaths);
        if (searchPaths.isEmpty()) {
            for (String name : List.of("copybook", "copy")) {
                Path candidate = inputDir.resolve(name);
                if (Files.isDirectory(candidate)) {
                    searchPaths.add(candidate);
                }
            }
        }
        Set<String> disabled = new LinkedHashSet<>(disabledRules);
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
