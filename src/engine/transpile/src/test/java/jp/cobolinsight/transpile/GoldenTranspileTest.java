package jp.cobolinsight.transpile;

import jp.cobolinsight.core.transpile.GeneratedFile;
import jp.cobolinsight.core.transpile.TargetLanguage;
import jp.cobolinsight.core.transpile.TranspileResult;
import jp.cobolinsight.transpile.emit.Transpiler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the transpilation result on two levels: (a) transpiling the same input twice produces
 * byte-identical generated files and line map (determinism), and (b) every generated file (runtime
 * helper, record classes, program) for all 9 samples (SYK001-SYK009) x {Python, Java} is byte-identical
 * to the committed golden (/golden/&lt;programId&gt;/&lt;fileName&gt;). To regenerate the golden files, run
 * with {@code -Dgolden.regenerate=true}; this wipes the golden directory clean before writing out all
 * generated files (leaving no stale golden files behind).
 */
class GoldenTranspileTest {

    private static final List<String> SAMPLES = List.of("SYK001.cbl", "SYK002.cbl", "SYK003.cbl",
            "SYK004.cbl", "SYK005.cbl", "SYK006.cbl", "SYK007.cbl", "SYK008.cbl", "SYK009.cbl");

    private static final boolean REGENERATE = Boolean.getBoolean("golden.regenerate");

    private static final Path GOLDEN_DIR =
            repoRoot().resolve("src/engine/transpile/src/test/resources/golden");

    private static TranspileResult transpile(String sample, TargetLanguage language) {
        return Transpiler.transpile(SampleModels.model(sample), SampleModels.sourceText(sample),
                language);
    }

    @Test
    void everyGeneratedFileMatchesCommittedGolden() throws IOException {
        if (REGENERATE) {
            regenerate();
            return;
        }
        TreeSet<Path> expected = new TreeSet<>();
        for (String sample : SAMPLES) {
            for (TargetLanguage language : TargetLanguage.values()) {
                TranspileResult result = transpile(sample, language);
                Path dir = GOLDEN_DIR.resolve(result.programId());
                for (GeneratedFile file : result.files()) {
                    Path golden = dir.resolve(file.fileName());
                    expected.add(golden);
                    assertTrue(Files.exists(golden), "golden が無い: " + rel(golden));
                    assertEquals(Files.readString(golden, StandardCharsets.UTF_8), file.content(),
                            rel(golden) + " が golden とバイト一致");
                }
            }
        }
        assertNoOrphanGolden(expected);
    }

    @Test
    void rerunIsByteIdentical() {
        for (String sample : SAMPLES) {
            for (TargetLanguage language : TargetLanguage.values()) {
                TranspileResult first = transpile(sample, language);
                TranspileResult second = transpile(sample, language);
                assertEquals(first.files().size(), second.files().size(),
                        sample + " / " + language + " の生成ファイル数");
                for (int i = 0; i < first.files().size(); i++) {
                    assertEquals(first.files().get(i).fileName(), second.files().get(i).fileName(),
                            sample + " / " + language + " の生成ファイル名の並び");
                    assertEquals(first.files().get(i).content(), second.files().get(i).content(),
                            sample + " / " + language + " / " + first.files().get(i).fileName()
                                    + " の再生成がバイト一致");
                }
                assertEquals(first.lineMap(), second.lineMap(),
                        sample + " / " + language + " の行対応が一致");
            }
        }
    }

    /** Confirms that the actual files under the golden directory match the expected set of generated files exactly, with none missing or extra. */
    private static void assertNoOrphanGolden(TreeSet<Path> expected) throws IOException {
        if (!Files.isDirectory(GOLDEN_DIR)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(GOLDEN_DIR)) {
            // Files whose name starts with . are version-control settings (.gitattributes), not generated output.
            List<Path> actual = walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted().toList();
            for (Path file : actual) {
                assertTrue(expected.contains(file.toAbsolutePath().normalize()),
                        "生成物に対応しない golden が残っている: " + rel(file));
            }
        }
    }

    private static void regenerate() {
        deleteRecursively(GOLDEN_DIR);
        for (String sample : SAMPLES) {
            for (TargetLanguage language : TargetLanguage.values()) {
                TranspileResult result = transpile(sample, language);
                Path dir = GOLDEN_DIR.resolve(result.programId());
                try {
                    Files.createDirectories(dir);
                    for (GeneratedFile file : result.files()) {
                        Files.writeString(dir.resolve(file.fileName()), file.content(),
                                StandardCharsets.UTF_8);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String rel(Path golden) {
        return GOLDEN_DIR.getParent().relativize(golden.toAbsolutePath().normalize()).toString()
                .replace('\\', '/');
    }

    /** Walks up to the ancestor holding samples as the repo root, so this resolves even when the working directory is under a module. */
    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("samples"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("samples ディレクトリが見つからない");
        }
        return dir;
    }
}
