package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Paths;
import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.ScanOutcome;
import jp.cobolinsight.core.transpile.TargetLanguage;
import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 増分解析の対象判定が、内容ハッシュの一致以外の要因も見ることの検証。SOURCE 行が
 * 解析由来かどうか、および適用するコードページが変わったかどうかを扱う。
 */
class ScanReanalysisTriggerTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    private Path copySamples() throws IOException {
        Path assets = tempDir.resolve("assets");
        for (String dir : List.of("bms", "cobol", "copybook", "jcl")) {
            Path from = SAMPLES.resolve(dir);
            Path to = assets.resolve(dir);
            Files.createDirectories(to);
            try (Stream<Path> children = Files.list(from)) {
                for (Path child : children.filter(Files::isRegularFile).toList()) {
                    Files.copy(child, to.resolve(child.getFileName().toString()));
                }
            }
        }
        return assets;
    }

    @Test
    void transpileRegisteredSourcesAreStillAnalyzedByScan() throws IOException {
        Path assets = copySamples();
        Path databaseFile = tempDir.resolve("shared.db");
        TranspileRunner.run(new TranspileRunner.Options(assets, databaseFile,
                List.of(assets.resolve("copybook")), Map.of(),
                List.of(TargetLanguage.PYTHON), tempDir.resolve("out")));

        // translate は行対応表の外部キーを満たすため SOURCE 行だけを登録する。内容ハッシュは
        // 一致するが解析済みではないため、続く scan はそれらを解析対象に含める必要がある。
        ScanOutcome.Summary summary = Pipelines.scan(assets, databaseFile,
                List.of(assets.resolve("copybook")), Map.of()).summary();

        assertEquals(List.of(), summary.skipped(), "translate が登録した行を解析済みとみなさないこと");
        assertEquals(0, summary.exitCode());
        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            for (var source : dao.findAllSources()) {
                assertTrue(dao.findNode(source.id()).isPresent(),
                        () -> "全ソースへ NODE 行があること: " + source.path());
            }
        }
    }

    @Test
    void copybookRemovalReanalyzesImportingPrograms() throws IOException {
        Path assets = copySamples();
        Path databaseFile = tempDir.resolve("removal.db");
        List<Path> copybookPaths = List.of(assets.resolve("copybook"));

        Pipelines.scan(assets, databaseFile, copybookPaths, Map.of()).summary();
        Files.delete(assets.resolve("copybook").resolve("SYKCPY2.cpy"));
        ScanOutcome.Summary summary = Pipelines.scan(assets, databaseFile,
                copybookPaths, Map.of()).summary();

        assertEquals(List.of("copybook/SYKCPY2.cpy"), summary.removed());
        assertTrue(summary.analyzed().contains("cobol/SYK002.cbl"),
                () -> "消えたコピー句を取り込むプログラムを解析し直すこと: " + summary.analyzed());
    }

    @Test
    void separateAssetFoldersCoexistInOneProjectFile() throws IOException {
        Path first = copySamples();
        Path second = tempDir.resolve("assets2");
        Files.createDirectories(second.resolve("cobol"));
        Files.copy(first.resolve("cobol").resolve("SYK003.cbl"),
                second.resolve("cobol").resolve("SYK003.cbl"));
        Path databaseFile = tempDir.resolve("shared-root.db");

        Pipelines.scan(first, databaseFile,
                List.of(first.resolve("copybook")), Map.of()).summary();
        ScanOutcome.Summary summary = Pipelines.scan(second, databaseFile,
                List.of(first.resolve("copybook")), Map.of()).summary();

        assertEquals(List.of(), summary.removed(), "別の資産フォルダの行を消さないこと");
        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            assertEquals(16, dao.findSourcesByRoot(Paths.rootOf(first)).size());
            assertEquals(1, dao.findSourcesByRoot(Paths.rootOf(second)).size());
        }
    }

    @Test
    void codepageOverrideChangeTriggersReanalysis() throws IOException {
        Path assets = copySamples();
        Path databaseFile = tempDir.resolve("codepage.db");
        List<Path> copybookPaths = List.of(assets.resolve("copybook"));

        Pipelines.scan(assets, databaseFile, copybookPaths, Map.of()).summary();
        ScanOutcome.Summary second = Pipelines.scan(assets, databaseFile,
                copybookPaths, Map.of("cobol/SYK005.cbl", "Shift_JIS")).summary();

        assertTrue(second.analyzed().contains("cobol/SYK005.cbl"),
                () -> "コードページ指定の変更で再解析すること: " + second.analyzed());
    }
}
