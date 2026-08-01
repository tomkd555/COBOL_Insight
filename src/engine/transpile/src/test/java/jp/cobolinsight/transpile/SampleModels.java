package jp.cobolinsight.transpile;

import jp.cobolinsight.cobolfrontend.Che4zCobolParser;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.DataItem;
import jp.cobolinsight.engineapi.source.DecodedSource;
import jp.cobolinsight.engineapi.source.EncodingInfo;
import jp.cobolinsight.engineapi.spi.ParseOutcome;

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

/** samples/cobol の資産を実パーサー(cobol-frontend)で解析し、テスト間で結果を共有する。 */
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

    /** samples/cobol の原ソーステキスト(UTF-8)。inline PERFORM VARYING の復元検証に使う。 */
    static String sourceText(String fileName) {
        try {
            return new String(Files.readAllBytes(COBOL_DIR.resolve(fileName)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 意味モデルのデータ項目木を平坦化し、名前で1件引く。 */
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
            // DecodedSource が要求する char 位置から原バイト列上のオフセットへの対応表を組む。
            // 日本語は1文字が複数バイトになるため char 位置とバイト位置は一致しない。
            // サロゲートペアを構成する2つの char は、同じバイト位置を指す。
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

    /** 作業ディレクトリがモジュール配下でも解決できるよう、samples を持つ親をリポジトリルートとして遡る。 */
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
