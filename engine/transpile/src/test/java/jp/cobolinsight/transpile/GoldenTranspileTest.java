package jp.cobolinsight.transpile;

import jp.cobolinsight.engineapi.transpile.GeneratedFile;
import jp.cobolinsight.engineapi.transpile.TargetLanguage;
import jp.cobolinsight.engineapi.transpile.TranspileResult;
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
 * A8 二層ゴールデン: (a) 同一入力を2回対訳して生成ファイルと行対応がバイト一致すること(決定論)、
 * (b) 全9本(SYK001〜SYK009)×{Python,Java}の全生成ファイル(ランタイムヘルパ・レコードクラス群・
 * プログラム)がコミット済み golden(/golden/&lt;programId&gt;/&lt;fileName&gt;)とバイト一致すること。
 * golden を作り直すときは {@code -Dgolden.regenerate=true} を付けて実行すると、golden ディレクトリを
 * 一掃してから全生成ファイルを書き出す(古い golden を残さない)。
 */
class GoldenTranspileTest {

    private static final List<String> SAMPLES = List.of("SYK001.cbl", "SYK002.cbl", "SYK003.cbl",
            "SYK004.cbl", "SYK005.cbl", "SYK006.cbl", "SYK007.cbl", "SYK008.cbl", "SYK009.cbl");

    private static final boolean REGENERATE = Boolean.getBoolean("golden.regenerate");

    private static final Path GOLDEN_DIR =
            repoRoot().resolve("engine/transpile/src/test/resources/golden");

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

    /** golden ディレクトリ配下の実ファイルが、期待した生成物の集合と過不足なく一致することを確認する。 */
    private static void assertNoOrphanGolden(TreeSet<Path> expected) throws IOException {
        if (!Files.isDirectory(GOLDEN_DIR)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(GOLDEN_DIR)) {
            List<Path> actual = walk.filter(Files::isRegularFile).sorted().toList();
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
