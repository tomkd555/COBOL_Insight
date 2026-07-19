package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.pipeline.AnalysisServices;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CONTROL_FLOW 段13ルールの ServiceLoader 登録・ID順・severity・段階・fixProducer の検証。 */
class CfgRuleRegistrationTest {

    private static final Map<String, Severity> EXPECTED = Map.ofEntries(
            Map.entry("R007", Severity.HIGH),
            Map.entry("R009", Severity.ADVISORY),
            Map.entry("R010", Severity.HIGH),
            Map.entry("R011", Severity.MEDIUM),
            Map.entry("R014", Severity.MEDIUM),
            Map.entry("R017", Severity.HIGH),
            Map.entry("R018", Severity.HIGH),
            Map.entry("R019", Severity.MEDIUM),
            Map.entry("R021", Severity.HIGH),
            Map.entry("R022", Severity.MEDIUM),
            Map.entry("R029", Severity.MEDIUM),
            Map.entry("R030", Severity.MEDIUM),
            Map.entry("R031", Severity.HIGH));

    @Test
    void thirteenControlFlowRulesDiscoveredInIdOrder() {
        List<Rule> rules = AnalysisServices.load().rules(AnalysisPhase.CONTROL_FLOW);
        assertEquals(List.of("R007", "R009", "R010", "R011", "R014", "R017", "R018", "R019",
                "R021", "R022", "R029", "R030", "R031"),
                rules.stream().map(Rule::id).toList());
    }

    @Test
    void severityPhaseAndFixProducerMatchCatalog() {
        for (Rule rule : AnalysisServices.load().rules(AnalysisPhase.CONTROL_FLOW)) {
            assertEquals(EXPECTED.get(rule.id()), rule.defaultSeverity(), rule.id());
            assertEquals(AnalysisPhase.CONTROL_FLOW, rule.phase(), rule.id());
            assertTrue(rule.fixProducer().isEmpty(), rule.id() + " は修正案生成の対象外であること");
        }
    }
}
