package jp.cobolinsight.app.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * `scan` サブコマンド。資産フォルダ(bms/cobol/copy|copybook/jcl 構成)を走査して解析し、
 * 結果をSQLiteプロジェクトファイルへ永続化して処理サマリをJSONで標準出力へ書く。
 * 任意で {@code --copy-expansion} により、COPY 文のインライン展開をJSONで書き出す。
 */
@Command(name = "scan", mixinStandardHelpOptions = true,
        description = "資産フォルダを解析し、結果をSQLiteへ永続化する")
public final class ScanCommand implements Callable<Integer> {

    @Mixin
    CommonScanOptions options;

    @Option(names = "--copy-expansion", paramLabel = "FILE",
            description = "COPY 文のインライン展開のJSON出力先")
    Path copyExpansionFile;

    @Override
    public Integer call() {
        ScanRunner.Options runnerOptions = options.toRunnerOptions();
        ScanRunner.Result result = ScanRunner.runWithGraph(runnerOptions);
        String expansionPath = null;
        if (copyExpansionFile != null) {
            writeString(copyExpansionFile, result.copyExpansions().toJson());
            expansionPath = copyExpansionFile.toString().replace('\\', '/');
        }
        System.out.println(result.summary()
                .toJson(runnerOptions.databaseFile().toString(), expansionPath));
        return result.summary().exitCode();
    }

    private static void writeString(Path file, String content) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
