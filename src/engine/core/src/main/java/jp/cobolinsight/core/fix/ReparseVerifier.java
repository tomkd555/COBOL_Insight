package jp.cobolinsight.core.fix;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.spi.CharsetProvider;
import jp.cobolinsight.core.spi.CobolParser;
import jp.cobolinsight.core.spi.ParseOutcome;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * 修正後ソースを COBOL パーサーで再パースし、固定形式の桁崩れ・トークン結合・リテラル破損などで
 * パースできない修正を検出する検証ゲート。
 *
 * <p>パーサーと文字コード復号器は呼出側が渡す。core は実装モジュールへコンパイル依存を持たず、
 * 実装の選択は app の EngineWiring が一箇所で行う。
 */
public final class ReparseVerifier {

    private final CobolParser parser;
    private final CharsetProvider charsetProvider;

    public ReparseVerifier(CobolParser parser, CharsetProvider charsetProvider) {
        this.parser = parser;
        this.charsetProvider = charsetProvider;
    }

    /** 復号済みソースを再パースする。 */
    public ReparseResult verify(DecodedSource fixedSource, List<Path> copybookSearchPaths) {
        ParseOutcome<CobolSemanticModel> outcome = parser.parse(fixedSource, copybookSearchPaths);
        return new ReparseResult(outcome.isSuccess(), outcome.failureFinding());
    }

    /**
     * 修正後バイト列を指定コードページで復号して再パースする。桁を修正適用時と同じ文字コードで
     * 数えるため、原本と同一のコードページ名を渡す。
     */
    public ReparseResult verify(String path, byte[] fixedBytes, String charsetName,
            List<Path> copybookSearchPaths) {
        return verify(charsetProvider.decode(path, fixedBytes, charsetName), copybookSearchPaths);
    }

    /** 復号済みテキストを再パースする。 */
    public ReparseResult verify(String path, String fixedText, List<Path> copybookSearchPaths) {
        // パーサーが読むのは path とテキストだけである。復号済みテキストを UTF-8 へ符号化して
        // 同一テキストへ復号し直し、engine-api の DecodedSource を得る。
        return verify(path, fixedText.getBytes(StandardCharsets.UTF_8), "UTF-8", copybookSearchPaths);
    }

}
