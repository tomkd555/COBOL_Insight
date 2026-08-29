package jp.cobolinsight.core.rule;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.FixProducer;

import java.util.List;
import java.util.Optional;

/**
 * The rule contract. Built-in rules are listed explicitly by {@code BuiltinRules}; declarative rules
 * are assembled from {@code rules.json}. Nothing is discovered at runtime, so a rule that is not
 * listed simply does not exist.
 */
public interface Rule {

    /** Identity, description, severity and pipeline requirements. */
    RuleMeta meta();

    List<Finding> evaluate(AnalysisContext ctx);

    /** Only rules with a canned fix (R004, R017, R018, R021) return a producer. */
    default Optional<FixProducer> fix() {
        return Optional.empty();
    }
}
