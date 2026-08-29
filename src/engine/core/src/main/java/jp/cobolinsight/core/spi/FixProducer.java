package jp.cobolinsight.core.spi;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FixSuggestion;

import java.util.Optional;

/**
 * 修正案生成の契約。finding に対するソース範囲→置換の最小編集を返す。適用(TokenStreamRewriter・
 * 固定形式ノーマライザ・再パース検証)は fix モジュールの責務である。
 */
public interface FixProducer {

    /** 修正案を生成できない finding に対しては empty を返す。 */
    Optional<FixSuggestion> produce(Finding finding, AnalysisContext context);
}
