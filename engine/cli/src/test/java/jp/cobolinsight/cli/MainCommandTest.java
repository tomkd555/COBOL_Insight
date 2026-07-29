package jp.cobolinsight.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** picocli配線と、ライセンスファイルの配布物への同梱の検証。 */
class MainCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void scanSubcommandRunsAndReturnsSuccess() throws IOException {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets.resolve("copybook"));
        Files.writeString(assets.resolve("copybook").resolve("MINI.cpy"),
                "       01  MINI-ITEM                  PIC X(10).\n");
        Path databaseFile = tempDir.resolve("scan.db");

        int exitCode = new CommandLine(new Main()).execute("scan", assets.toString(),
                "--db", databaseFile.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(databaseFile), "SQLiteプロジェクトファイルが作られること");
    }

    @Test
    void codepageFlagOverridesDetection() throws IOException {
        Path samples = Path.of("..", "..", "samples").toAbsolutePath().normalize();
        Path assets = tempDir.resolve("assets-sjis");
        Files.createDirectories(assets.resolve("cobol"));
        Files.copy(samples.resolve("encoding").resolve("SYKENC1_SJIS.cbl"),
                assets.resolve("cobol").resolve("SYKENC1_SJIS.cbl"));
        Path databaseFile = tempDir.resolve("scan-sjis.db");

        new CommandLine(new Main()).execute("scan", assets.toString(),
                "--db", databaseFile.toString(),
                "--codepage", "cobol/SYKENC1_SJIS.cbl=Shift_JIS");

        try (var database = jp.cobolinsight.persistence.PersistenceDatabase.open(databaseFile)) {
            var dao = new jp.cobolinsight.persistence.PersistenceDao(database.connection());
            var source = dao.findSourceByPath(ScanRunner.rootOf(assets), "cobol/SYKENC1_SJIS.cbl").orElseThrow();
            var info = dao.findEncodingInfo(source.id()).orElseThrow();
            assertTrue(info.manualOverride(), "CLIフラグの手動指定が自動判別を上書きすること");
            assertEquals("windows-31j", info.detectedCharset());
        }
    }

    @Test
    void vendorLicenseFilesAreBundledIntoTheDistribution() {
        assertNotNull(Main.class.getResourceAsStream("/licenses/che4z/LICENSE.md"),
                "Che4z(EPL-2.0)のライセンスファイルがcli.jarへ同梱されること");
        assertNotNull(Main.class.getResourceAsStream("/licenses/mapa/LICENSE"),
                "MAPA(MIT)のライセンスファイルがcli.jarへ同梱されること");
    }
}
