package jp.cobolinsight.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * `scan` サブコマンド。資産フォルダ(bms/cobol/copy|copybook/jcl 構成)を走査して解析し、
 * 結果をSQLiteプロジェクトファイルへ永続化して処理サマリをJSONで標準出力へ書く。
 */
@Command(name = "scan", mixinStandardHelpOptions = true,
        description = "資産フォルダを解析し、結果をSQLiteへ永続化する")
public final class ScanCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "INPUT_DIR", description = "資産フォルダ")
    Path inputDir;

    @Option(names = "--db", paramLabel = "FILE", defaultValue = "cobol-insight.db",
            description = "SQLiteプロジェクトファイル(既定: ${DEFAULT-VALUE})")
    Path databaseFile;

    @Option(names = "--copybook-path", paramLabel = "DIR",
            description = "コピー句探索パス(既定: INPUT_DIR配下のcopybook・copy)")
    List<Path> copybookPaths = new ArrayList<>();

    @Option(names = "--codepage", paramLabel = "FILE=CHARSET",
            description = "ファイル単位のコードページ手動指定(相対パスまたはファイル名=コードページ)。自動判別に優先する")
    Map<String, String> codepageOverrides = new LinkedHashMap<>();

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
        ScanRunner.Summary summary = ScanRunner.run(
                new ScanRunner.Options(inputDir, databaseFile, searchPaths, codepageOverrides));
        System.out.println(summary.toJson());
        return summary.exitCode();
    }
}
