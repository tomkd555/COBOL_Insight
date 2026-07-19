package jp.cobolinsight.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** lintサブコマンドのpicocli配線・SARIFファイル出力・終了コード分岐の検証。 */
class LintCommandTest {

    @TempDir
    Path tempDir;

    private Path assets(String name, String cobolSource) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir.resolve("cobol"));
        Files.writeString(dir.resolve("cobol").resolve(name.toUpperCase() + ".cbl"),
                cobolSource, StandardCharsets.UTF_8);
        return dir;
    }

    private static final String CLEAN = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  CLEAN1.",
            "       ENVIRONMENT DIVISION.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-COUNT                    PIC 9(03).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           MOVE 1 TO WS-COUNT",
            "           DISPLAY WS-COUNT",
            "           GOBACK.",
            "");

    private static final String WARNING = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  WARN1.",
            "       ENVIRONMENT DIVISION.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-COUNT                    PIC 9(03).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           PERFORM 1000-STEP",
            "           GOBACK.",
            "       1000-STEP.",
            "           MOVE 1 TO WS-COUNT.",
            "");

    private static final String ERROR = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  ERR1.",
            "       ENVIRONMENT DIVISION.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  API-KEY                     PIC X(08) VALUE 'PW12345'.",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           DISPLAY API-KEY",
            "           GOBACK.",
            "");

    private static final String COPY_USER = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  COPYUSE.",
            "       ENVIRONMENT DIVISION.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "           COPY EXTC.",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           GOBACK.",
            "");

    private static final String EXT_COPYBOOK =
            "       01  EXT-UNUSED                  PIC X(01).\n";

    @Test
    void cleanAssetsExitWithSuccessAndWriteSarifFile() throws IOException {
        Path dir = assets("clean", CLEAN);
        Path sarif = tempDir.resolve("clean.sarif");

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", sarif.toString());

        assertEquals(0, exitCode, "検出なしは成功(0)であること");
        assertTrue(Files.exists(sarif), "SARIFファイルが書き出されること");
        String json = Files.readString(sarif, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\":\"2.1.0\""));
        assertTrue(json.contains("\"results\":[]"));
    }

    @Test
    void warningLevelFindingsExitWithOne() throws IOException {
        Path dir = assets("warn", WARNING);

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", tempDir.resolve("warn.sarif").toString());

        assertEquals(1, exitCode, "警告あり=1であること");
    }

    @Test
    void disabledRuleSuppressesItsFindings() throws IOException {
        Path dir = assets("warnoff", WARNING.replace("WARN1", "WARNOFF"));

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", tempDir.resolve("warnoff.sarif").toString(),
                "--disable-rule", "R008");

        assertEquals(0, exitCode, "R008を無効化すると警告が消え成功(0)になること");
    }

    @Test
    void copybookFindingIsRelativizedAgainstCopybookPath() throws IOException {
        Path dir = assets("copyuse", COPY_USER);
        Path external = tempDir.resolve("externalcpy");
        Files.createDirectories(external);
        Files.writeString(external.resolve("EXTC.cpy"), EXT_COPYBOOK, StandardCharsets.UTF_8);
        Path sarif = tempDir.resolve("copyuse.sarif");

        new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", sarif.toString(), "--copybook-path", external.toString());

        String json = Files.readString(sarif, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"ruleId\":\"R002\""), json);
        assertTrue(json.contains("\"uri\":\"EXTC.cpy\""),
                "--copybook-path のディレクトリ基準で相対化されること: " + json);
        assertTrue(!json.contains("externalcpy"),
                "コピー句の絶対パスがSARIFに漏れないこと: " + json);
    }

    @Test
    void errorLevelFindingsExitWithTwo() throws IOException {
        Path dir = assets("err", ERROR);
        Path sarif = tempDir.resolve("err.sarif");

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", sarif.toString());

        assertEquals(2, exitCode, "エラーあり=2であること");
        String json = Files.readString(sarif, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"ruleId\":\"R026\""));
        assertTrue(json.contains("\"level\":\"error\""));
    }
}
