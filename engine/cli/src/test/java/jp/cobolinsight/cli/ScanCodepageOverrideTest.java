package jp.cobolinsight.cli;

import jp.cobolinsight.persistence.PersistenceDao;
import jp.cobolinsight.persistence.PersistenceDatabase;
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

/** コードページ手動指定が自動判別を上書きすることの検証(05_開発計画.md §3.1)。 */
class ScanCodepageOverrideTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    @Test
    void manualCodepageOverridesDetection() throws IOException {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets.resolve("cobol"));
        Files.copy(SAMPLES.resolve("encoding").resolve("SYKENC1_SJIS.cbl"),
                assets.resolve("cobol").resolve("SYKENC1_SJIS.cbl"));
        Path databaseFile = tempDir.resolve("scan.db");

        ScanRunner.run(new ScanRunner.Options(assets, databaseFile, List.of(),
                Map.of("cobol/SYKENC1_SJIS.cbl", "Shift_JIS")));

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            var source = dao.findSourceByPath("cobol/SYKENC1_SJIS.cbl").orElseThrow();
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

        ScanRunner.run(new ScanRunner.Options(assets, databaseFile, List.of(), Map.of()));

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            var source = dao.findSourceByPath("cobol/SYKENC1_SJIS.cbl").orElseThrow();
            var info = dao.findEncodingInfo(source.id()).orElseThrow();
            assertFalse(info.manualOverride());
            assertEquals("windows-31j", info.detectedCharset());
        }
    }
}
