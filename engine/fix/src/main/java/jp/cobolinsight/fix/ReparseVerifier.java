package jp.cobolinsight.fix;

import jp.cobolinsight.engineapi.pipeline.AnalysisServices;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.source.DecodedSource;
import jp.cobolinsight.engineapi.spi.CharsetProvider;
import jp.cobolinsight.engineapi.spi.CobolParser;
import jp.cobolinsight.engineapi.spi.ParseOutcome;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * 修正後ソースを COBOL パーサーで再パースし、固定形式の桁崩れ・トークン結合・リテラル破損などで
 * パースできない修正を検出する検証ゲート。
 *
 * <p>パーサーと文字コード復号器は {@link AnalysisServices#load()} が ServiceLoader で束ねる
 * {@link CobolParser} / {@link CharsetProvider} 実装を用いる。fix 本体は engine-api だけへ
 * コンパイル依存し、実装は実行時クラスパスへ同梱する。
 */
public final class ReparseVerifier {

    private final CobolParser parser;
    private final CharsetProvider charsetProvider;

    public ReparseVerifier() {
        AnalysisServices services = AnalysisServices.load();
        this.parser = single(services.cobolParsers(), "CobolParser");
        this.charsetProvider = single(services.charsetProviders(), "CharsetProvider");
    }

    /** 復号済みソースを再パースする。 */
    public ReparseResult verify(DecodedSource fixedSource, List<Path> copybookSearchPaths) {
        ParseOutcome<CobolSemanticModel> outcome = parser.parse(fixedSource, copybookSearchPaths);
        return new ReparseResult(outcome.isSuccess(), outcome.failureFinding());
    }

    /**
     * 修正後バイト列を指定コードページで復号して再パースする。fix の往復と同じ符号で数えるため、
     * 原本と同一のコードページ名を渡す。
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

    private static <T> T single(List<T> implementations, String contractName) {
        if (implementations.isEmpty()) {
            throw new IllegalStateException(contractName + " の実装が実行時クラスパスに無い");
        }
        return implementations.get(0);
    }
}
