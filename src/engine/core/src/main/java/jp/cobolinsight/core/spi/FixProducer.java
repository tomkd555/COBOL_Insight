package jp.cobolinsight.core.spi;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FixSuggestion;

import java.util.Optional;

/**
 * The contract for generating fix suggestions. Returns the minimal source-range-to-replacement
 * edit for a finding. Applying it (TokenStreamRewriter, the fixed-format normalizer, reparse
 * verification) is the fix module's responsibility.
 */
public interface FixProducer {

    /** Returns empty for a finding that no fix suggestion can be generated for. */
    Optional<FixSuggestion> produce(Finding finding, AnalysisContext context);
}
