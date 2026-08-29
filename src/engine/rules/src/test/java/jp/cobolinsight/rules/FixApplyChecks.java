package jp.cobolinsight.rules;

import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.fix.ByteSpliceApplier;
import jp.cobolinsight.core.fix.ReparseResult;
import jp.cobolinsight.core.fix.ReparseVerifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * FixProducer が返す編集を実際に原本へバイトスプライス適用し、修正後ソースが再パースできるかを
 * 検証するテスト補助。桁崩れ・トークン結合・リテラル破損を伴う編集は再パースが失敗するため、
 * 位置と置換文字列の単体検証に加えて、生成した修正が固定形式として成立することを裏づける。
 */
public final class FixApplyChecks {

    private FixApplyChecks() {
    }

    /** 原本へ編集群を適用し、UTF-8 で復号して再パースした結果を返す。 */
    public static ReparseResult applyAndReparse(String originalFile, List<TextEdit> edits,
            List<Path> copybookSearchPaths) {
        byte[] fixed;
        try {
            fixed = new ByteSpliceApplier().apply(Path.of(originalFile), edits);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ReparseVerifier().verify(originalFile, fixed, "UTF-8", copybookSearchPaths);
    }
}
