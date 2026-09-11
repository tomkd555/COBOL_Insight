package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.SourceDiscovery;

import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The input, output and analysis options `scan` takes in (as a picocli {@code @Mixin}).
 */
final class CommonScanOptions {

    @Parameters(index = "0", paramLabel = "INPUT_DIR", description = "資産フォルダ")
    Path inputDir;

    @Option(names = "--db", paramLabel = "FILE", defaultValue = "cobol-insight.db",
            description = "SQLiteプロジェクトファイル(既定: ${DEFAULT-VALUE})")
    Path databaseFile;

    @Option(names = "--copybook-path", paramLabel = "DIR",
            description = "コピー句探索パス(既定: 走査で見つかったコピー句の置き場所)")
    List<Path> copybookPaths = new ArrayList<>();

    @Option(names = "--proc-path", paramLabel = "DIR",
            description = "PROC・INCLUDEメンバーの探索パス(既定: 資産フォルダ内)")
    List<Path> procedureLibraryPaths = new ArrayList<>();

    @Option(names = "--codepage", paramLabel = "FILE=CHARSET",
            description = "ファイル単位のコードページ手動指定(相対パスまたはファイル名=コードページ)。自動判別に優先する")
    Map<String, String> codepageOverrides = new LinkedHashMap<>();

    /**
     * With no explicit setting, wherever the walk found copybooks becomes the COPY search path.
     * Deciding by folder name instead would leave a COPY unresolved as soon as someone put the
     * copybook somewhere else.
     *
     * <p>This walks the folder of its own accord, so it is for the callers that have no pipeline
     * run to take the answer from — {@code save}'s reparse and {@code fix}'s write-back. Whatever
     * goes through a pipeline passes its {@code --copybook-path} in as given and lets
     * {@code SourceSet.copybookSearchPaths()} decide from the run's own walk.
     */
    static List<Path> resolveCopybookPaths(Path inputDir, List<Path> specified) {
        return specified.isEmpty()
                ? SourceDiscovery.discover(inputDir).copybookDirectories() : List.copyOf(specified);
    }
}
