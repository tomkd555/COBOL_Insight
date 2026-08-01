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

/**
 * ServiceLoader登録と、ID・severity・段階の検証。
 * SYNTAX 段には構文ルール(R系7件)と SQL助言ルール(S系6件)が同居するため、本テストは R系に
 * 限定して突合する(S系の登録は SqlRuleRegistrationTest が検証する)。
 */
class SyntaxRuleRegistrationTest {

    private static List<Rule> syntaxCobolRules() {
        return AnalysisServices.load().rules(AnalysisPhase.SYNTAX).stream()
                .filter(rule -> rule.id().startsWith("R"))
                .toList();
    }

    @Test
    void sevenSyntaxRulesAreDiscoveredInIdOrder() {
        assertEquals(List.of("R002", "R006", "R008", "R013", "R023", "R024", "R026"),
                syntaxCobolRules().stream().map(Rule::id).toList());
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
        for (Rule rule : syntaxCobolRules()) {
            assertEquals(expected.get(rule.id()), rule.defaultSeverity(), rule.id());
            assertTrue(rule.fixProducer().isEmpty(),
                    rule.id() + " は修正案生成の対象外であること");
        }
    }
}
