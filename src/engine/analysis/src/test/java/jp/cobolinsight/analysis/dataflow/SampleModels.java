package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.frontend.cobol.Che4zCobolParser;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
import jp.cobolinsight.core.spi.ParseOutcome;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Parses the 9 samples/cobol files with the real parser and shares the results across tests. */
final class SampleModels {

    static final List<String> SAMPLE_FILES = List.of(
            "SYK001.cbl", "SYK002.cbl", "SYK003.cbl", "SYK004.cbl", "SYK005.cbl",
            "SYK006.cbl", "SYK007.cbl", "SYK008.cbl", "SYK009.cbl");

    private static final Path REPO_ROOT = findRepoRoot();
    private static final Path COBOL_DIR = REPO_ROOT.resolve("samples").resolve("cobol");
    private static final List<Path> COPYBOOK_PATHS =
            List.of(REPO_ROOT.resolve("samples").resolve("copybook"));

    private static final Map<String, CobolSemanticModel> CACHE = new ConcurrentHashMap<>();

    private SampleModels() {
    }

    static CobolSemanticModel model(String fileName) {
        return CACHE.computeIfAbsent(fileName, name -> {
            ParseOutcome<CobolSemanticModel> outcome =
                    new Che4zCobolParser().parse(load(COBOL_DIR.resolve(name)), COPYBOOK_PATHS);
            return outcome.value().orElseThrow(() -> new AssertionError(
                    name + " のパースが失敗した: " + outcome.failureFinding().orElse(null)));
        });
    }

    private static DecodedSource load(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            String text = new String(bytes, StandardCharsets.UTF_8);
            int[] offsets = new int[text.length()];
            int byteOffset = 0;
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                int charCount = Character.charCount(cp);
                for (int j = 0; j < charCount; j++) {
                    offsets[i + j] = byteOffset;
                }
                byteOffset += new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
                i += charCount;
            }
            return new DecodedSource(file.toString(), text, bytes, offsets,
                    new EncodingInfo("UTF-8", 1.0, false, false));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path findRepoRoot() {
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
