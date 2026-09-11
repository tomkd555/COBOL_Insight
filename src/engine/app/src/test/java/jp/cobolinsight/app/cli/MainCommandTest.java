package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Paths;
import jp.cobolinsight.core.pipeline.ExitCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verification of picocli wiring and the license files bundling into the distribution. */
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
        Path samples = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();
        Path assets = tempDir.resolve("assets-sjis");
        Files.createDirectories(assets.resolve("cobol"));
        Files.copy(samples.resolve("encoding").resolve("SYKENC1_SJIS.cbl"),
                assets.resolve("cobol").resolve("SYKENC1_SJIS.cbl"));
        Path databaseFile = tempDir.resolve("scan-sjis.db");

        new CommandLine(new Main()).execute("scan", assets.toString(),
                "--db", databaseFile.toString(),
                "--codepage", "cobol/SYKENC1_SJIS.cbl=Shift_JIS");

        try (var database = jp.cobolinsight.app.persistence.PersistenceDatabase.open(databaseFile)) {
            var dao = new jp.cobolinsight.app.persistence.PersistenceDao(database.connection());
            var source = dao.findSourceByPath(Paths.rootOf(assets), "cobol/SYKENC1_SJIS.cbl").orElseThrow();
            assertEquals("windows-31j", source.codepage(),
                    "CLIフラグの手動指定が自動判別を上書きすること");
        }
    }

    /**
     * An uncaught exception must never reach the user as a stack trace: the execution exception
     * handler wired in {@link Main#commandLine()} turns it into one Japanese line on stderr and
     * {@link ExitCodes#ERRORS}, instead of picocli's default stack-trace dump.
     */
    @Test
    void uncaughtExceptionPrintsOneJapaneseLineInsteadOfAStackTrace() throws Exception {
        CommandLine cmd = Main.commandLine();
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int exitCode;
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            exitCode = cmd.getExecutionExceptionHandler()
                    .handleExecutionException(new NoSuchFileException("missing.cbl"), cmd, null);
        } finally {
            System.setErr(original);
        }

        assertEquals(ExitCodes.ERRORS, exitCode);
        String stderr = captured.toString(StandardCharsets.UTF_8);
        assertEquals("エラー: ファイルが見つかりません: missing.cbl。処理を中止しました。"
                + System.lineSeparator(), stderr);
    }

    /**
     * What the walk dropped goes to standard error, as it does for every other subcommand. The
     * summary JSON carries the same files, but a reader of the console sees nothing of it.
     */
    @Test
    void scanReportsUndecidedFilesOnStandardError() throws IOException {
        Path assets = tempDir.resolve("undecided-assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("NOTES.dat"), "これは資産ではありません。\n",
                StandardCharsets.UTF_8);
        Path databaseFile = tempDir.resolve("undecided.db");

        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            new CommandLine(new Main()).execute("scan", assets.toString(),
                    "--db", databaseFile.toString());
        } finally {
            System.setErr(original);
        }

        String stderr = captured.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("警告: ") && stderr.contains("NOTES.dat"),
                () -> "種別を判別できなかったファイルを標準エラーへ書くこと: " + stderr);
    }

    @Test
    void vendorLicenseFilesAreBundledIntoTheDistribution() {
        assertNotNull(Main.class.getResourceAsStream("/licenses/che4z/LICENSE.md"),
                "Che4z(EPL-2.0)のライセンスファイルがcli.jarへ同梱されること");
        assertNotNull(Main.class.getResourceAsStream("/licenses/mapa/LICENSE"),
                "MAPA(MIT)のライセンスファイルがcli.jarへ同梱されること");
    }
}
