package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.SourceDiscovery;
import jp.cobolinsight.core.source.AssetKind;

import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The input, output and analysis options `scan` and `call-graph` share (taken in as a picocli
 * {@code @Mixin}).
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

    @Option(names = "--codepage", paramLabel = "FILE=CHARSET",
            description = "ファイル単位のコードページ手動指定(相対パスまたはファイル名=コードページ)。自動判別に優先する")
    Map<String, String> codepageOverrides = new LinkedHashMap<>();

    List<Path> resolvedCopybookPaths() {
        return resolveCopybookPaths(inputDir, copybookPaths);
    }

    /**
     * With no explicit setting, wherever the walk found copybooks becomes the COPY search path.
     * Deciding by folder name instead would leave a COPY unresolved as soon as someone put the
     * copybook somewhere else.
     */
    static List<Path> resolveCopybookPaths(Path inputDir, List<Path> specified) {
        List<Path> searchPaths = new ArrayList<>(specified);
        if (!searchPaths.isEmpty()) {
            return searchPaths;
        }
        Set<Path> parents = new LinkedHashSet<>();
        for (SourceDiscovery.DiscoveredFile file
                : SourceDiscovery.discover(inputDir).filesOf(Set.of(AssetKind.COPYBOOK))) {
            Path parent = file.absPath().getParent();
            if (parent != null) {
                parents.add(parent);
            }
        }
        searchPaths.addAll(parents);
        return searchPaths;
    }
}
