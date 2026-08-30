package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.EngineWiring;
import jp.cobolinsight.app.fix.UnifiedDiffFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrates the copybook-internal fix mechanism. Because findings for the auto-fix target
 * rules (R004/R017/R018) are tied to the semantic model's sourceFile (= the program body), the
 * samples never produce a fix that originates from a copybook. So this test demonstrates that the
 * mechanism is in place through importing-program resolution against the real samples, and a
 * synthesized copybook fix applied in present-only mode (does not rewrite the original, presents
 * the diff and lists the importers).
 */
class CopybookFixMechanismTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    private static Map<String, String> programSources() {
        Map<String, String> byRel = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(SAMPLES.resolve("cobol"))) {
            files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".cbl"))
                    .sorted()
                    .forEach(p -> byRel.put("cobol/" + p.getFileName(),
                            readString(p)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return byRel;
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void resolvesImportingProgramsForEachCopybook() {
        Map<String, String> programs = programSources();
        // SYKCPY1 is imported by SYK001 (REPLACING), SYK002 (REPLACING), and SYK003.
        assertEquals(List.of("cobol/SYK001.cbl", "cobol/SYK002.cbl", "cobol/SYK003.cbl"),
                CopybookImporters.of("SYKCPY1", programs));
        // SYKCPY2 is imported only by SYK002.
        assertEquals(List.of("cobol/SYK002.cbl"), CopybookImporters.of("SYKCPY2", programs));
        // SYKCPY3 is imported by SYK006 and SYK007.
        assertEquals(List.of("cobol/SYK006.cbl", "cobol/SYK007.cbl"),
                CopybookImporters.of("SYKCPY3", programs));
        // A copybook with no importing program yields an empty list.
        assertTrue(CopybookImporters.of("NOSUCHCPY", programs).isEmpty());
    }

    @Test
    void baseNameStripsDirectoryAndExtension() {
        assertEquals("SYKCPY1", CopybookImporters.baseName("copybook/SYKCPY1.cpy"));
        assertEquals("SYKCPY3", CopybookImporters.baseName("SYKCPY3.cpy"));
    }

    @Test
    void copybookFixIsPresentedOnlyAndListsImporters(@TempDir Path out) throws IOException {
        Path originalCopybook = SAMPLES.resolve("copybook").resolve("SYKCPY1.cpy");
        byte[] originalBytes = Files.readAllBytes(originalCopybook);
        String originalText = new String(originalBytes, StandardCharsets.UTF_8);

        // A synthesized fix that inserts a check statement into the copybook (synthesized because the real R004/R017/R018 ties to the body).
        String insertedLine = "           IF WS-SYNTH-STATUS NOT = '00' END-IF.";
        String fixedText = originalText + insertedLine + "\n";
        List<String> importers =
                List.of("cobol/SYK001.cbl", "cobol/SYK002.cbl", "cobol/SYK003.cbl");
        FixRunner.FileFix copybookFix = new FixRunner.FileFix("copybook/SYKCPY1.cpy", "UTF-8",
                originalText, fixedText, fixedText.getBytes(StandardCharsets.UTF_8),
                List.of("FILE STATUS 検査を挿入する"), true, importers);

        FixApplyCommand.ApplyOutcome outcome = FixApplyCommand.applyFixes(
                List.of(copybookFix), out, EngineWiring.reparseVerifier(), List.of());

        // Present-only: not written to the output destination, and not listed among the written programs.
        assertTrue(outcome.written().isEmpty(), "コピー句修正は書き出さないこと");
        assertFalse(Files.exists(out.resolve("copybook").resolve("SYKCPY1.cpy")),
                "コピー句は出力先へ書き出されないこと");
        // Aggregated with the list of importing programs attached.
        assertEquals(1, outcome.copybookFixes().size());
        FixApplyCommand.CopybookFix reported = outcome.copybookFixes().get(0);
        assertEquals("copybook/SYKCPY1.cpy", reported.relPath());
        assertEquals(importers, reported.importers());
        // The original copybook is unchanged.
        assertArrayEquals(originalBytes, Files.readAllBytes(originalCopybook),
                "原本コピー句は書き換えないこと");

        // Diff presentation: a unified diff containing the inserted line can be computed.
        List<String> diff = new UnifiedDiffFormatter()
                .unifiedDiff(copybookFix.relPath(), originalText, fixedText);
        assertTrue(diff.stream().anyMatch(line -> line.equals("+" + insertedLine)),
                "コピー句の差分に挿入行が現れること: " + diff);
    }
}
