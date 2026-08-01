package jp.cobolinsight.cli;

import jp.cobolinsight.engineapi.source.AssetKind;

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
 * `scan`・`call-graph` 両サブコマンドが共有する入力・保存先・解析設定のオプション群
 * (picocli の @Mixin として取り込む)。
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

    /** ScanRunner の実行オプションへ変換する。コピー句探索パスの既定解決を含む。 */
    ScanRunner.Options toRunnerOptions() {
        return new ScanRunner.Options(inputDir, databaseFile,
                resolveCopybookPaths(inputDir, copybookPaths), codepageOverrides);
    }

    /**
     * 指定が無い場合、走査で見つかったコピー句の置き場所をコピー句探索パスとする。
     * フォルダ名で決めないのは、コピー句がどこに置かれていても COPY 文が解決できるようにする
     * ためである。
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
