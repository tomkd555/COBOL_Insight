package jp.cobolinsight.rules;

import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in catalogue as a whole. This replaces the per-category registration tests, which
 * carried a hand-maintained count and had to be edited every time a rule was added; what matters is
 * that every rule is reachable, uniquely identified and fully described.
 */
class BuiltinRulesTest {

    @Test
    void ruleIdsAreUnique() {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (Rule rule : BuiltinRules.all()) {
            if (!seen.add(rule.meta().id())) {
                duplicates.add(rule.meta().id());
            }
        }
        assertTrue(duplicates.isEmpty(), "duplicate rule ids: " + duplicates);
    }

    /**
     * A built-in rule is on unless the measurement in {@code corpus/rule-hits.md} showed it to be
     * noise. Pinning the list here keeps a rule from being switched off in passing.
     */
    @Test
    void onlyTheMeasuredStyleRulesShipDisabled() {
        assertEquals(List.of("R008"), BuiltinRules.all().stream()
                .filter(rule -> !rule.meta().defaultEnabled())
                .map(rule -> rule.meta().id())
                .toList());
    }

    /** Every rule describes itself: the GUI shows this text and has no copy of its own. */
    @TestFactory
    List<DynamicTest> everyRuleCarriesCompleteMetadata() {
        return BuiltinRules.all().stream()
                .map(rule -> DynamicTest.dynamicTest(rule.meta().id(), () -> {
                    RuleMeta meta = rule.meta();
                    assertFalse(meta.name().isBlank(), "name");
                    assertFalse(meta.category().isBlank(), "category");
                    assertFalse(meta.summary().isBlank(), "summary");
                    assertFalse(meta.rationale().isBlank(), "rationale");
                    assertFalse(meta.detection().isBlank(), "detection");
                    assertFalse(meta.remedy().isBlank(), "remedy");
                    assertFalse(meta.commands().isEmpty(), "commands");
                    assertFalse(meta.targets().isEmpty(), "targets");
                    assertTrue(!meta.commands().contains(Command.FIX) || rule.fix().isPresent(),
                            "a rule that runs under fix must carry a FixProducer");
                }))
                .toList();
    }
}
