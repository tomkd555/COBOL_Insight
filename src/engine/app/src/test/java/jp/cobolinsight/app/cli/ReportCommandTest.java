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

/** Verifies the report subcommand's picocli wiring, HTML/text file output, and exit-code branching. */
class ReportCommandTest {

    @TempDir
    Path tempDir;

    /** A program with a cursor declaration (no FOR clause, no OPTIMIZE FOR). Triggers SQL advice S004 (medium -> warning). */
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
        Path sarif = tempDir.resolve("report.sarif");
        Path sqlSarif = tempDir.resolve("report-sql.sarif");
        Path html = tempDir.resolve("report.html");
        Path text = tempDir.resolve("report.txt");

        new CommandLine(new Main()).execute("scan", dir.toString(), "--db", db.toString());
        new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", sarif.toString(), "--sql-sarif", sqlSarif.toString());

        int exitCode = new CommandLine(new Main()).execute("report",
                "--db", db.toString(), "--sarif", sarif.toString(),
                "--sql-sarif", sqlSarif.toString(),
                "--html", html.toString(), "--text", text.toString());

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

    /**
     * The inventory comes from the whole-folder scan either way, so a report built on a scoped
     * lint reads as an estate that is largely clean unless it says which part was analysed.
     */
    @Test
    void aReportOfAScopedLintNamesTheRangeItCovers() throws IOException {
        Path dir = assets();
        Path db = tempDir.resolve("scoped.db");
        Path sarif = tempDir.resolve("scoped.sarif");
        Path sqlSarif = tempDir.resolve("scoped-sql.sarif");
        Path html = tempDir.resolve("scoped.html");
        Path text = tempDir.resolve("scoped.txt");

        new CommandLine(new Main()).execute("scan", dir.toString(), "--db", db.toString());
        new CommandLine(new Main()).execute("lint", dir.toString(),
                "--sarif", sarif.toString(), "--sql-sarif", sqlSarif.toString(),
                "--scope", "cobol");
        new CommandLine(new Main()).execute("report",
                "--db", db.toString(), "--sarif", sarif.toString(),
                "--sql-sarif", sqlSarif.toString(),
                "--html", html.toString(), "--text", text.toString());

        String note = "この結果の指摘は cobol の範囲だけを解析したものです。";
        assertTrue(Files.readString(html, StandardCharsets.UTF_8).contains(note),
                "HTML が解析した範囲を述べること");
        assertTrue(Files.readString(text, StandardCharsets.UTF_8).contains(note),
                "テキストが解析した範囲を述べること");
    }
}
