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

/** reportサブコマンドのpicocli配線・HTML/テキストファイル出力・終了コード分岐の検証。 */
class ReportCommandTest {

    @TempDir
    Path tempDir;

    /** カーソル宣言(FOR句なし・OPTIMIZE FORなし)を持つプログラム。SQL指摘 S004(中→警告)を含む。 */
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

    private Path assets() throws IOException {
        Path dir = tempDir.resolve("assets");
        Files.createDirectories(dir.resolve("cobol"));
        Files.writeString(dir.resolve("cobol").resolve("CURDECL.cbl"), CURSOR,
                StandardCharsets.UTF_8);
        return dir;
    }

    @Test
    void reportWritesHtmlAndTextAndExitsWithWarningFromSqlAdvice() throws IOException {
        Path dir = assets();
        Path db = tempDir.resolve("proj.db");
        Path html = tempDir.resolve("report.html");
        Path text = tempDir.resolve("report.txt");

        new CommandLine(new Main()).execute("scan", dir.toString(), "--db", db.toString());

        int exitCode = new CommandLine(new Main()).execute("report", dir.toString(),
                "--db", db.toString(), "--html", html.toString(), "--text", text.toString());

        assertEquals(1, exitCode, "SQL指摘 S004(中→警告)を含むため終了コード1であること");
        assertTrue(Files.exists(html), "HTMLレポートが書き出されること");
        assertTrue(Files.exists(text), "テキストレポートが書き出されること");

        String htmlText = Files.readString(html, StandardCharsets.UTF_8);
        assertTrue(htmlText.startsWith("<!DOCTYPE html>"), "HTML文書であること");
        assertTrue(htmlText.contains("S004"), "SQL指摘 S004 が HTML に載ること: " + htmlText);
        assertTrue(htmlText.contains("cobol/CURDECL.cbl"),
                "資産がインベントリに載ること");

        String textReport = Files.readString(text, StandardCharsets.UTF_8);
        assertTrue(textReport.contains("呼出関係の要約"), "テキストに呼出関係の要約節があること");
        assertTrue(textReport.contains("S004"), "テキストに SQL指摘 S004 が載ること");
    }
}
