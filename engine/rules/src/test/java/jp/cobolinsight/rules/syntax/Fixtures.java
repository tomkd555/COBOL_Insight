package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.cobolfrontend.Che4zCobolParser;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.source.DecodedSource;
import jp.cobolinsight.engineapi.source.EncodingInfo;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.ParseOutcome;
import jp.cobolinsight.rules.SourceTextIndex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 合成fixtureのCOBOLソースを実パーサーで解析し、ルールへ渡すAnalysisContextを組み立てる。 */
final class Fixtures {

    private Fixtures() {
    }

    /** fixtureテキストをファイルへ書き出して解析する。失敗はテスト失敗とする。 */
    static CobolSemanticModel parse(Path dir, String fileName, String text, Path... copybookDirs) {
        Path file = dir.resolve(fileName);
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ParseOutcome<CobolSemanticModel> outcome =
                new Che4zCobolParser().parse(decoded(file.toString(), text), List.of(copybookDirs));
        return outcome.value().orElseThrow(() -> new AssertionError(
                fileName + " のパースが失敗した: " + outcome.failureFinding().orElse(null)));
    }

    /** 意味モデルとソーステキスト索引だけを持つ構文段階のAnalysisContext。 */
    static AnalysisContext context(List<CobolSemanticModel> models, Map<String, String> texts) {
        return AnalysisContext.of(models, List.of(), List.of(), List.of(), Optional.empty(),
                Map.of(SourceTextIndex.class, new SourceTextIndex(texts)));
    }

    /**
     * テキストを DecodedSource へ包む。offsets は文字位置からUTF-8バイト位置への対応表であり、
     * サロゲートペアの2文字目にもコードポイント先頭のバイト位置を入れる。
     */
    static DecodedSource decoded(String path, String text) {
        int[] offsets = new int[text.length()];
        int byteOffset = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            for (int j = 0; j < charCount; j++) {
                offsets[i + j] = byteOffset;
            }
            byteOffset += new String(Character.toChars(codePoint))
                    .getBytes(StandardCharsets.UTF_8).length;
            i += charCount;
        }
        return new DecodedSource(path, text, text.getBytes(StandardCharsets.UTF_8), offsets,
                new EncodingInfo("UTF-8", 1.0, false, false));
    }
}
