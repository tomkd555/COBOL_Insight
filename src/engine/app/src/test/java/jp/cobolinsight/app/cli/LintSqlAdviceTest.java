package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.finding.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the lint subcommand's --sql-sarif output and exit-code branching from SQL advice. */
class LintSqlAdviceTest {

    @TempDir
    Path tempDir;

    private Path assets(String name, String cobolSource) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir.resolve("cobol"));
        Files.writeString(dir.resolve("cobol").resolve(name.toUpperCase() + ".cbl"),
                cobolSource, StandardCharsets.UTF_8);
        return dir;
    }

    /** A program with a cursor declaration (no FOR clause, no OPTIMIZE FOR). Triggers S004 (medium -> warning). */
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

    /** A program with no embedded SQL. Produces zero SQL findings. */
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
    void assetsWithoutSqlExitWithSuccessAndWriteSqlSarifFile() throws IOException {
        Path dir = assets("nosql", NO_SQL);
        Path sarif = tempDir.resolve("nosql.sarif");
        Path sqlSarif = tempDir.resolve("nosql-sql.sarif");

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", sarif.toString(), "--sql-sarif", sqlSarif.toString());

        assertEquals(0, exitCode, "SQL指摘なしは成功(0)であること");
        assertTrue(Files.exists(sqlSarif), "SQL SARIFファイルが書き出されること");
        String json = Files.readString(sqlSarif, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\":\"2.1.0\""));
        assertTrue(json.contains("\"results\":[]"));
        assertTrue(!json.contains("\"id\":\"parse-failure\""),
                "解析そのものの指摘は、それを載せる SARIF だけが規則として挙げること: " + json);
    }

    @Test
    void readOnlyCursorAdviceExitsWithOne() throws IOException {
        Path dir = assets("cur", CURSOR);
        Path sarif = tempDir.resolve("cur.sarif");
        Path sqlSarif = tempDir.resolve("cur-sql.sarif");

        int exitCode = new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", sarif.toString(), "--sql-sarif", sqlSarif.toString());

        assertEquals(1, exitCode, "S004(中→警告)を含むため終了コード1であること");
        String json = Files.readString(sqlSarif, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"ruleId\":\"S004\""), json);
        assertTrue(!json.contains("\"ruleId\":\"R"), "バグ検出(R接頭辞)が混じらないこと: " + json);
    }

    @Test
    void disabledRuleSuppressesItsFindings() throws IOException {
        Path dir = assets("curoff", CURSOR.replace("CURDECL", "CUROFF"));
        Path sarif = tempDir.resolve("curoff.sarif");
        Path sqlSarif = tempDir.resolve("curoff-sql.sarif");
        Path config = tempDir.resolve("rules.json");
        Files.writeString(config, "{\"version\": 2, \"rules\": {\"S004\": {\"enabled\": false}}}",
                StandardCharsets.UTF_8);

        new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", sarif.toString(), "--sql-sarif", sqlSarif.toString(),
                "--rules", config.toString());

        String json = Files.readString(sqlSarif, StandardCharsets.UTF_8);
        assertTrue(!json.contains("\"ruleId\":\"S004\""),
                "S004を無効化すると当該検出が消えること: " + json);
    }

    /**
     * A decode/parse failure is a finding of the folder as a whole, not of SQL advice, so it
     * belongs in the LINT SARIF only; duplicating it into the SQL SARIF made {@code report} count
     * it three times over (DB + both files).
     */
    @Test
    void unparseableMemberIsReportedInLintSarifOnlyNotSql() throws IOException {
        Path dir = assets("badbms", NO_SQL);
        Path bmsDir = Files.createDirectories(dir.resolve("bms"));
        Files.writeString(bmsDir.resolve("BROKEN.bms"), "!@#$%^&*() garbage tokens 123\n",
                StandardCharsets.UTF_8);

        LintRunner.Result result = LintRunner.run(new LintRunner.Options(dir, List.of(), Map.of()));

        String ruleIdField = "\"ruleId\":\"" + Finding.PARSE_FAILURE_RULE_ID + "\"";
        assertTrue(result.sarifJson().contains(ruleIdField),
                "パース失敗は LINT SARIF に載ること: " + result.sarifJson());
        assertTrue(!result.sqlSarifJson().contains(ruleIdField),
                "SQL SARIF には重複して載らないこと: " + result.sqlSarifJson());
    }
}
