package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.pipeline.AnalysisServices;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DATA_FLOW 段11ルールの ServiceLoader 登録・ID順・severity・段階・fixProducer の検証。 */
class DataFlowRuleRegistrationTest {

    /** 定型修正の FixProducer を提供する DATA_FLOW 段ルール。 */
    private static final Set<String> FIX_PRODUCERS = Set.of("R004");

    private static final Map<String, Severity> EXPECTED = Map.ofEntries(
            entry("R001", Severity.HIGH),
            entry("R003", Severity.HIGH),
            entry("R004", Severity.HIGH),
            entry("R005", Severity.HIGH),
            entry("R012", Severity.HIGH),
            entry("R015", Severity.HIGH),
            entry("R016", Severity.HIGH),
            entry("R020", Severity.HIGH),
            entry("R025", Severity.ADVISORY),
            entry("R027", Severity.MEDIUM),
            entry("R028", Severity.MEDIUM));

    @Test
    void elevenDataFlowRulesDiscoveredInIdOrder() {
        List<Rule> rules = AnalysisServices.load().rules(AnalysisPhase.DATA_FLOW);
        assertEquals(List.of("R001", "R003", "R004", "R005", "R012", "R015", "R016", "R020",
                        "R025", "R027", "R028"),
                rules.stream().map(Rule::id).toList());
    }

    @Test
    void severityPhaseAndFixProducerMatchCatalog() {
        for (Rule rule : AnalysisServices.load().rules(AnalysisPhase.DATA_FLOW)) {
            assertEquals(EXPECTED.get(rule.id()), rule.defaultSeverity(), rule.id());
            assertEquals(AnalysisPhase.DATA_FLOW, rule.phase(), rule.id());
            if (FIX_PRODUCERS.contains(rule.id())) {
                assertTrue(rule.fixProducer().isPresent(), rule.id() + " は修正案生成器を持つこと");
            } else {
                assertTrue(rule.fixProducer().isEmpty(),
                        rule.id() + " は修正案生成器を持たないこと");
            }
        }
    }
}
