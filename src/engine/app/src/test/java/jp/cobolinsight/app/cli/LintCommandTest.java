package jp.cobolinsight.app.cli;

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

    /** 警告どまりの資産。どこからも呼ばれない段落(R011)を含む。 */
    private static final String WARNING = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  WARN1.",
            "       ENVIRONMENT DIVISION.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-COUNT                    PIC 9(03).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           MOVE 1 TO WS-COUNT",
            "           DISPLAY WS-COUNT",
            "           GOBACK.",
            "       1000-STEP.",
            "           MOVE 2 TO WS-COUNT.",
            "");

    /** エラーを含む資産。VALUE 句へ認証情報を直書きした項目(R026)を含む。 */
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

    /** 参照されない項目だけを持つ外部コピー句。未使用変数(R002)をコピー句側の位置で検出させる。 */
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

    /** ルールの有効・無効は設定ファイルだけが決める。 */
    @Test
    void rulesFileSuppressesItsFindings() throws IOException {
        Path dir = assets("warncfg", WARNING.replace("WARN1", "WARNCFG"));
        Path config = tempDir.resolve("rules.json");
        Files.writeString(config, "{\"version\": 2, \"rules\": {\"R011\": {\"enabled\": false}}}",
                StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", tempDir.resolve("warncfg.sarif").toString(),
                "--rules", config.toString());

        assertEquals(0, exitCode, "設定ファイルで R011 を無効化すると成功(0)になること");
    }

    /** 設定ファイルは複数件を並べられる。1件目だけを読んで打ち切らないことを固める。 */
    @Test
    void rulesFileSuppressesEveryListedRule() throws IOException {
        Path dir = assets("warnmany", WARNING.replace("WARN1", "WARNMANY"));
        Path config = tempDir.resolve("rules-many.json");
        Files.writeString(config, "{\"version\": 2, \"rules\": {\"R001\": {\"enabled\": false},"
                        + " \"R011\": {\"enabled\": false}}}",
                StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", tempDir.resolve("warnmany.sarif").toString(),
                "--rules", config.toString());

        assertEquals(0, exitCode, "並べた R001・R011 のいずれも無効化されること");
    }

    /**
     * 未作成の設定ファイルは、1件も無効にしていない設定として扱う。GUI は設定の有無に
     * かかわらず --rules を常に渡すため、入れたばかりの環境ではファイルがまだ無い。
     */
    @Test
    void missingRulesFileDisablesNothing() throws IOException {
        Path dir = assets("cfgmiss", CLEAN.replace("CLEAN1", "CFGMISS"));

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", tempDir.resolve("cfgmiss.sarif").toString(),
                "--rules", tempDir.resolve("absent.json").toString());

        assertEquals(0, exitCode, "指摘の無い資産は、設定ファイルが無くてもそのまま通ること");
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
