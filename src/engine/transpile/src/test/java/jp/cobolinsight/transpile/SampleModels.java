package jp.cobolinsight.transpile;

import jp.cobolinsight.frontend.cobol.Che4zCobolParser;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
import jp.cobolinsight.core.spi.ParseOutcome;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Parses the assets under samples/cobol with the real parser (cobol-frontend) and shares the results across tests. */
final class SampleModels {

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

    /** The original source text (UTF-8) under samples/cobol. Used to verify recovery of inline PERFORM VARYING. */
    static String sourceText(String fileName) {
        try {
            return new String(Files.readAllBytes(COBOL_DIR.resolve(fileName)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Flattens the data item tree of the semantic model and looks up a single entry by name. */
    static DataItem findItem(CobolSemanticModel model, String name) {
        List<DataItem> flat = new ArrayList<>();
        flatten(model.dataItems(), flat);
        return flat.stream().filter(i -> i.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("DataItem が見つからない: " + name));
    }

    private static void flatten(List<DataItem> items, List<DataItem> out) {
        for (DataItem item : items) {
            out.add(item);
            flatten(item.children(), out);
        }
    }

    private static DecodedSource load(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            String text = new String(bytes, StandardCharsets.UTF_8);
            // Build the mapping DecodedSource requires, from char position to offset in the original byte array.
            // A char position and a byte position do not coincide for Japanese, since one character spans multiple bytes.
            // The two chars making up a surrogate pair point to the same byte position.
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

    /** Walks up to the ancestor holding samples as the repo root, so this resolves even when the working directory is under a module. */
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
