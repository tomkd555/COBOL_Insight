package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Paths;
import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that a manual codepage specification overrides automatic detection. */
class ScanCodepageOverrideTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    @Test
    void manualCodepageOverridesDetection() throws IOException {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets.resolve("cobol"));
        Files.copy(SAMPLES.resolve("encoding").resolve("SYKENC1_SJIS.cbl"),
                assets.resolve("cobol").resolve("SYKENC1_SJIS.cbl"));
        Path databaseFile = tempDir.resolve("scan.db");

        Pipelines.scan(assets, databaseFile, List.of(),
                Map.of("cobol/SYKENC1_SJIS.cbl", "Shift_JIS")).summary();

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            var source = dao.findSourceByPath(Paths.rootOf(assets), "cobol/SYKENC1_SJIS.cbl").orElseThrow();
            var info = dao.findEncodingInfo(source.id()).orElseThrow();
            assertTrue(info.manualOverride(), "手動指定が記録されること");
            assertEquals("windows-31j", info.detectedCharset());
            assertEquals(1.0, info.confidence());
            assertEquals("windows-31j", source.codepage());
        }
    }

    @Test
    void withoutOverrideDetectionRunsAutomatically() throws IOException {
        Path assets = tempDir.resolve("assets2");
        Files.createDirectories(assets.resolve("cobol"));
        Files.copy(SAMPLES.resolve("encoding").resolve("SYKENC1_SJIS.cbl"),
                assets.resolve("cobol").resolve("SYKENC1_SJIS.cbl"));
        Path databaseFile = tempDir.resolve("scan2.db");

        Pipelines.scan(assets, databaseFile, List.of(), Map.of()).summary();

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            var source = dao.findSourceByPath(Paths.rootOf(assets), "cobol/SYKENC1_SJIS.cbl").orElseThrow();
            var info = dao.findEncodingInfo(source.id()).orElseThrow();
            assertFalse(info.manualOverride());
            assertEquals("windows-31j", info.detectedCharset());
        }
    }
}
