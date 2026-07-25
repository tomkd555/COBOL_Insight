package jp.cobolinsight.cli;

import jp.cobolinsight.fix.ReparseVerifier;
import jp.cobolinsight.fix.UnifiedDiffFormatter;
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
 * コピー句内修正の機構を表明する。M7 の自動修正対象(R004/R017/R018)の finding は意味モデルの
 * sourceFile(=プログラム本体)に係留するため、samples ではコピー句由来の修正は発生しない。そこで
 * 機構が備わることを、実 samples に対する取り込みプログラム解決と、合成したコピー句修正の
 * present-only 適用(原本を書き換えず差分提示・取り込み一覧併記)で表明する。
 */
class CopybookFixMechanismTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

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
        // SYKCPY1 は SYK001(REPLACING)・SYK002(REPLACING)・SYK003 が取り込む。
        assertEquals(List.of("cobol/SYK001.cbl", "cobol/SYK002.cbl", "cobol/SYK003.cbl"),
                CopybookImporters.of("SYKCPY1", programs));
        // SYKCPY2 は SYK002 のみ。
        assertEquals(List.of("cobol/SYK002.cbl"), CopybookImporters.of("SYKCPY2", programs));
        // SYKCPY3 は SYK006・SYK007。
        assertEquals(List.of("cobol/SYK006.cbl", "cobol/SYK007.cbl"),
                CopybookImporters.of("SYKCPY3", programs));
        // 取り込むプログラムが無いコピー句は空。
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

        // コピー句へ検査文を挿入した合成修正(実際の R004/R017/R018 は本体へ係留するため合成)。
        String insertedLine = "           IF WS-SYNTH-STATUS NOT = '00' END-IF.";
        String fixedText = originalText + insertedLine + "\n";
        List<String> importers =
                List.of("cobol/SYK001.cbl", "cobol/SYK002.cbl", "cobol/SYK003.cbl");
        FixRunner.FileFix copybookFix = new FixRunner.FileFix("copybook/SYKCPY1.cpy", "UTF-8",
                originalText, fixedText, fixedText.getBytes(StandardCharsets.UTF_8),
                List.of("FILE STATUS 検査を挿入する"), true, importers);

        FixApplyCommand.ApplyOutcome outcome = FixApplyCommand.applyFixes(
                List.of(copybookFix), out, new ReparseVerifier(), List.of());

        // present-only: 出力先へ書き出さず、書き出したプログラム一覧にも載らない。
        assertTrue(outcome.written().isEmpty(), "コピー句修正は書き出さないこと");
        assertFalse(Files.exists(out.resolve("copybook").resolve("SYKCPY1.cpy")),
                "コピー句は出力先へ書き出されないこと");
        // 取り込みプログラム一覧を併記して集約する。
        assertEquals(1, outcome.copybookFixes().size());
        FixApplyCommand.CopybookFix reported = outcome.copybookFixes().get(0);
        assertEquals("copybook/SYKCPY1.cpy", reported.relPath());
        assertEquals(importers, reported.importers());
        // 原本コピー句は不変。
        assertArrayEquals(originalBytes, Files.readAllBytes(originalCopybook),
                "原本コピー句は書き換えないこと");

        // 差分提示: 挿入行を含む unified diff が算出できること。
        List<String> diff = new UnifiedDiffFormatter()
                .unifiedDiff(copybookFix.relPath(), originalText, fixedText);
        assertTrue(diff.stream().anyMatch(line -> line.equals("+" + insertedLine)),
                "コピー句の差分に挿入行が現れること: " + diff);
    }
}
