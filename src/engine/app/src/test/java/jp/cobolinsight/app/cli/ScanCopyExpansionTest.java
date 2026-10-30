package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.ScanOutcome;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the copybook inline-expansion artifact written by `scan --copy-expansion`. From this
 * JSON, the GUI builds the view where the copybook is inserted at the position of the original
 * COPY statement.
 */
class ScanCopyExpansionTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples", "seeded").toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    private ScanOutcome.CopyExpansions runScan() {
        return Pipelines.scan(SAMPLES,
                        tempDir.resolve("scan.db"), List.of(SAMPLES.resolve("copybook")), Map.of())
                .copyExpansions();
    }

    private static ScanOutcome.CopyExpansions.ProgramExpansion programOf(
            ScanOutcome.CopyExpansions expansions, String relPath) {
        return expansions.programs().stream()
                .filter(program -> program.relPath().equals(relPath))
                .findFirst()
                .orElseThrow(() -> new AssertionError(relPath + " の展開が無い"));
    }

    @Test
    void carriesCopyStatementLineCopybookNameAndReplacedLines() {
        var program = programOf(runScan(), "cobol/SYK001.cbl");
        assertEquals("SYK001", program.programId());
        assertEquals(1, program.expansions().size(), "SYK001 の COPY 文は1件");

        var expansion = program.expansions().get(0);
        assertEquals(32, expansion.copyStatementLine());
        assertEquals("SYKCPY1", expansion.copybookName());
        assertEquals("copybook/SYKCPY1.cpy", expansion.copybookPath(),
                "コピー句のパスは資産フォルダからの相対パスであること");
        assertTrue(expansion.lines().stream()
                        .anyMatch(line -> line.text().contains("ORD1-受注レコード")),
                "REPLACING 適用後のテキストを持つこと");
    }

    @Test
    void programsAreSortedAndOnlyThoseWithCopyStatementsAppear() {
        ScanOutcome.CopyExpansions expansions = runScan();
        List<String> paths = expansions.programs().stream()
                .map(ScanOutcome.CopyExpansions.ProgramExpansion::relPath).toList();
        assertEquals(paths.stream().sorted().toList(), paths, "相対パス昇順であること");
        assertFalse(paths.contains("cobol/SYK008.cbl"), "COPY 文を持たないプログラムは載せないこと");
    }

    @Test
    void commandWritesJsonFileAndReportsItInTheSummary() throws IOException {
        Path json = tempDir.resolve("expansion.json");
        int exitCode = new CommandLine(new Main()).execute("scan", SAMPLES.toString(),
                "--db", tempDir.resolve("cmd.db").toString(),
                "--copy-expansion", json.toString());

        assertEquals(0, exitCode);
        String text = Files.readString(json, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"path\":\"cobol/SYK001.cbl\""), text.substring(0, 200));
        assertTrue(text.contains("\"copyStatementLine\":32"));
        assertTrue(text.contains("\"copybookName\":\"SYKCPY1\""));
        assertTrue(text.contains("\"copybookLine\":7"));
    }

    /** A line a nested COPY brought in names its copybook, relative to the asset folder. */
    @Test
    void aNestedCopybookLineCarriesItsOwnPath() throws IOException {
        Path assets = tempDir.resolve("nested");
        Files.createDirectories(assets.resolve("copy"));
        Files.writeString(assets.resolve("copy/OUTER.cpy"), String.join("\n",
                "           DISPLAY 'OUTER'.",
                "           COPY INNER.",
                "           DISPLAY 'AFTER'.", ""), StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("copy/INNER.cpy"),
                "           DISPLAY 'INNER'.\n", StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("NESTPGM.cbl"), String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID.  NESTPGM.",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           COPY OUTER.",
                "           STOP RUN.", ""), StandardCharsets.UTF_8);

        String json = Pipelines.scan(assets, tempDir.resolve("nested.db"), List.of(), Map.of())
                .copyExpansions().toJson();

        assertTrue(json.contains("{\"copybookLine\":1,\"text\":\"           DISPLAY 'INNER'.\","
                + "\"copybookPath\":\"copy/INNER.cpy\"}"), json);
        assertTrue(json.contains("{\"copybookLine\":3,\"text\":\"           DISPLAY 'AFTER'.\"}"),
                json);
    }

    /**
     * Where a COPY stands on a line shared with others: two COPY statements on one line, a COPY
     * after code, and a second COPY after a copybook of two lines. Each COPY has one expansion
     * standing on its line, its lines in copybook order.
     */
    @Test
    void eachCopyOfALineSharedWithOthersHasItsOwnExpansion() throws IOException {
        Path assets = tempDir.resolve("shared-lines");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("FIRST.cpy"), "           DISPLAY 'FIRST'.\n",
                StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("SECOND.cpy"), "           DISPLAY 'SECOND'.\n",
                StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("TWO.cpy"),
                "           DISPLAY 'T1'.\n           DISPLAY 'T2'.\n", StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("ONE.cpy"), "           DISPLAY 'O1'.\n",
                StandardCharsets.UTF_8);
        for (String[] program : new String[][] {
                {"TWOCOPY", "           COPY FIRST. COPY SECOND."},
                {"MIDCOPY", "           DISPLAY 'A' COPY FIRST."},
                {"TRAIL", "           DISPLAY 'A' COPY TWO. DISPLAY 'B' COPY ONE."}}) {
            Files.writeString(assets.resolve(program[0] + ".cbl"), String.join("\n",
                    "       IDENTIFICATION DIVISION.",
                    "       PROGRAM-ID.  " + program[0] + ".",
                    "       PROCEDURE DIVISION.",
                    "       MAIN-PARA.",
                    program[1],
                    "           STOP RUN.", ""), StandardCharsets.UTF_8);
        }

        ScanOutcome outcome = Pipelines.scan(assets, tempDir.resolve("shared-lines.db"),
                List.of(), Map.of());
        String json = outcome.copyExpansions().toJson();

        assertEquals(0, outcome.summary().exitCode(), outcome.summary().toJson("db", null));
        assertTrue(json.contains("\"expansions\":[" + expansion("FIRST", "'FIRST'") + ","
                + expansion("SECOND", "'SECOND'") + "]"), json);
        assertTrue(json.contains("\"expansions\":[" + expansion("FIRST", "'FIRST'") + "]"),
                json);
        assertTrue(json.contains("\"expansions\":[" + expansion("TWO", "'T1'", "'T2'") + ","
                + expansion("ONE", "'O1'") + "]"), json);
    }

    /** The JSON of one expansion at line 5 whose copybook lines DISPLAY the values given. */
    private static String expansion(String copybook, String... displayed) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < displayed.length; i++) {
            lines.append(i == 0 ? "" : ",").append("{\"copybookLine\":").append(i + 1)
                    .append(",\"text\":\"           DISPLAY ").append(displayed[i])
                    .append(".\"}");
        }
        return "{\"copyStatementLine\":5,\"copybookName\":\"" + copybook
                + "\",\"copybookPath\":\"" + copybook + ".cpy\",\"lines\":[" + lines + "]}";
    }

    @Test
    void summaryOmitsTheKeyWhenNoFileIsRequested() {
        ScanOutcome.Summary summary = new ScanOutcome.Summary(List.of(), List.of(), List.of(), 0, 0);
        assertFalse(summary.toJson("p.db", null).contains("copyExpansionFile"));
        assertTrue(summary.toJson("p.db", "e.json").contains("\"copyExpansionFile\":\"e.json\""));
    }
}
