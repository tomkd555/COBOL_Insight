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

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

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

    @Test
    void summaryOmitsTheKeyWhenNoFileIsRequested() {
        ScanOutcome.Summary summary = new ScanOutcome.Summary(List.of(), List.of(), List.of(), 0, 0);
        assertFalse(summary.toJson("p.db", null).contains("copyExpansionFile"));
        assertTrue(summary.toJson("p.db", "e.json").contains("\"copyExpansionFile\":\"e.json\""));
    }
}
