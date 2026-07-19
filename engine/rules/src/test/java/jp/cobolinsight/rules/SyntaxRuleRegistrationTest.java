package jp.cobolinsight.rules;

import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.pipeline.AnalysisServices;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ServiceLoader登録と、ルールカタログ(docs/06)どおりのID・severity・段階の検証。 */
class SyntaxRuleRegistrationTest {

    @Test
    void sevenSyntaxRulesAreDiscoveredInIdOrder() {
        List<Rule> rules = AnalysisServices.load().rules(AnalysisPhase.SYNTAX);
        assertEquals(List.of("R002", "R006", "R008", "R013", "R023", "R024", "R026"),
                rules.stream().map(Rule::id).toList());
    }

    @Test
    void defaultSeveritiesMatchRuleCatalog() {
        Map<String, Severity> expected = Map.of(
                "R002", Severity.LOW,
                "R006", Severity.LOW,
                "R008", Severity.MEDIUM,
                "R013", Severity.MEDIUM,
                "R023", Severity.MEDIUM,
                "R024", Severity.MEDIUM,
                "R026", Severity.HIGH);
        for (Rule rule : AnalysisServices.load().rules(AnalysisPhase.SYNTAX)) {
            assertEquals(expected.get(rule.id()), rule.defaultSeverity(), rule.id());
            assertTrue(rule.fixProducer().isEmpty(),
                    rule.id() + " は修正案生成の対象外(docs/06)であること");
        }
    }
}
