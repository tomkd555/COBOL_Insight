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

/** sql-lintサブコマンドのpicocli配線・SARIFファイル出力・終了コード分岐の検証。 */
class SqlAdviseCommandTest {

    @TempDir
    Path tempDir;

    private Path assets(String name, String cobolSource) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir.resolve("cobol"));
        Files.writeString(dir.resolve("cobol").resolve(name.toUpperCase() + ".cbl"),
                cobolSource, StandardCharsets.UTF_8);
        return dir;
    }

    /** カーソル宣言(FOR句なし・OPTIMIZE FORなし)を持つプログラム。S004(中→警告)を含む。 */
    private static final String CURSOR = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  CURDECL.",
            "       ENVIRONMENT DIVISION.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-CD                       PIC X(08).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC SQL",
            "               DECLARE C1 CURSOR FOR",
            "                   SELECT SHOHIN_CD FROM SYKDB.ZAIKOM",
            "           END-EXEC",
            "           GOBACK.",
            "");

    /** 埋め込みSQLを持たないプログラム。SQL指摘は0件。 */
    private static final String NO_SQL = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  NOSQL.",
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

    @Test
    void assetsWithoutSqlExitWithSuccessAndWriteSarifFile() throws IOException {
        Path dir = assets("nosql", NO_SQL);
        Path sarif = tempDir.resolve("nosql.sarif");

        int exitCode = new CommandLine(new Main()).execute("sql-lint", dir.toString(),
                "--sarif", sarif.toString());

        assertEquals(0, exitCode, "SQL指摘なしは成功(0)であること");
        assertTrue(Files.exists(sarif), "SARIFファイルが書き出されること");
        String json = Files.readString(sarif, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\":\"2.1.0\""));
        assertTrue(json.contains("\"results\":[]"));
    }

    @Test
    void readOnlyCursorAdviceExitsWithOne() throws IOException {
        Path dir = assets("cur", CURSOR);
        Path sarif = tempDir.resolve("cur.sarif");

        int exitCode = new CommandLine(new Main()).execute("sql-lint", dir.toString(),
                "--sarif", sarif.toString());

        assertEquals(1, exitCode, "S004(中→警告)を含むため終了コード1であること");
        String json = Files.readString(sarif, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"ruleId\":\"S004\""), json);
        assertTrue(!json.contains("\"ruleId\":\"R"), "バグ検出(R接頭辞)が混じらないこと: " + json);
    }

    @Test
    void disabledRuleSuppressesItsFindings() throws IOException {
        Path dir = assets("curoff", CURSOR.replace("CURDECL", "CUROFF"));
        Path sarif = tempDir.resolve("curoff.sarif");
        Path config = tempDir.resolve("rules.json");
        Files.writeString(config, "{\"version\": 2, \"rules\": {\"S004\": {\"enabled\": false}}}",
                StandardCharsets.UTF_8);

        new CommandLine(new Main()).execute("sql-lint", dir.toString(),
                "--sarif", sarif.toString(), "--rules", config.toString());

        String json = Files.readString(sarif, StandardCharsets.UTF_8);
        assertTrue(!json.contains("\"ruleId\":\"S004\""),
                "S004を無効化すると当該検出が消えること: " + json);
    }
}
