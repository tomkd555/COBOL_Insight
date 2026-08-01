package jp.cobolinsight.cobolfrontend;

import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.source.DecodedSource;
import jp.cobolinsight.engineapi.source.EncodingInfo;
import jp.cobolinsight.engineapi.spi.ParseOutcome;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** samples 配下の資産を DecodedSource として読み込み、パース結果をテスト間で共有する。 */
final class TestSources {

    static final Path REPO_ROOT = findRepoRoot();
    static final Path COBOL_DIR = REPO_ROOT.resolve("samples").resolve("cobol");
    static final List<Path> COPYBOOK_PATHS = List.of(REPO_ROOT.resolve("samples").resolve("copybook"));

    private static final Map<String, ParseOutcome<CobolSemanticModel>> CACHE = new ConcurrentHashMap<>();

    private TestSources() {
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

    static DecodedSource load(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return fromText(file.toString(), new String(bytes, StandardCharsets.UTF_8), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static DecodedSource fromText(String path, String text) {
        return fromText(path, text, text.getBytes(StandardCharsets.UTF_8));
    }

    private static DecodedSource fromText(String path, String text, byte[] bytes) {
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
        return new DecodedSource(path, text, bytes, offsets,
                new EncodingInfo("UTF-8", 1.0, false, false));
    }

    /** samples/cobol の1本をパースし、結果をキャッシュする。 */
    static ParseOutcome<CobolSemanticModel> parseSample(String fileName) {
        return CACHE.computeIfAbsent(fileName, name -> new Che4zCobolParser()
                .parse(load(COBOL_DIR.resolve(name)), COPYBOOK_PATHS));
    }

    static CobolSemanticModel model(String fileName) {
        ParseOutcome<CobolSemanticModel> outcome = parseSample(fileName);
        return outcome.value().orElseThrow(() -> new AssertionError(
                fileName + " のパースが失敗した: " + outcome.failureFinding().orElse(null)));
    }
}
