package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.rules.BuiltinRules;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Analyzes the whole samples set with the 13 CONTROL_FLOW-stage rules and checks the result
 * against the detection locations that samples/expected-results.md lists as the ground truth.
 * R017 asserts only a containment relation (contains SYK001:85 and SYK002:130, and does not
 * detect OPEN/CLOSE lines); the other rules assert an exact match of the detection set.
 */
class CfgSamplesAcceptanceTest {

    private record Hit(String file, int line, FindingLevel level) {
    }

    private static Set<Hit> hitsOf(String ruleId) {
        AnalysisContext context = CfgFixtures.samples();
        Rule rule = BuiltinRules.all().stream()
                .filter(r -> r.meta().id().equals(ruleId)).findFirst().orElseThrow();
        Set<Hit> hits = new LinkedHashSet<>();
        for (Finding finding : rule.evaluate(context)) {
            hits.add(new Hit(baseName(finding.location().file()), finding.location().line(),
                    finding.level()));
        }
        return hits;
    }

    private static String baseName(String file) {
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return slash < 0 ? file : file.substring(slash + 1);
    }

    private static Hit hit(String cbl, int line, FindingLevel level) {
        return new Hit(cbl, line, level);
    }

    @Test
    void r007PerformThruInterruptGoTo() {
        assertEquals(Set.of(hit("SYK002.cbl", 124, FindingLevel.ERROR)), hitsOf("R007"));
    }

    @Test
    void r011UnreachableAndUnusedParagraph() {
        assertEquals(Set.of(
                hit("SYK004.cbl", 47, FindingLevel.WARNING),
                hit("SYK005.cbl", 40, FindingLevel.WARNING)), hitsOf("R011"));
    }

    @Test
    void r017FileStatusUncheckedContainsReadAndRewriteAndSkipsOpenClose() {
        Set<Hit> hits = hitsOf("R017");
        assertTrue(hits.contains(hit("SYK001.cbl", 85, FindingLevel.ERROR)),
                () -> "SYK001:85 READ を含むこと: " + hits);
        assertTrue(hits.contains(hit("SYK002.cbl", 130, FindingLevel.ERROR)),
                () -> "SYK002:130 REWRITE を含むこと: " + hits);
        assertTrue(hits.stream().allMatch(h -> h.level() == FindingLevel.ERROR),
                () -> "全て error レベルであること: " + hits);
        // Must not detect OPEN/CLOSE statement lines.
        for (Hit forbidden : List.of(
                hit("SYK001.cbl", 79, FindingLevel.ERROR),
                hit("SYK001.cbl", 134, FindingLevel.ERROR),
                hit("SYK002.cbl", 68, FindingLevel.ERROR),
                hit("SYK006.cbl", 82, FindingLevel.ERROR),
                hit("SYK007.cbl", 56, FindingLevel.ERROR),
                hit("SYK007.cbl", 92, FindingLevel.ERROR))) {
            assertTrue(!hits.contains(forbidden),
                    () -> "OPEN/CLOSE 行を検出しないこと: " + forbidden + " in " + hits);
        }
    }

    @Test
    void r018SqlCodeUnchecked() {
        assertEquals(Set.of(
                hit("SYK006.cbl", 119, FindingLevel.ERROR),
                hit("SYK007.cbl", 89, FindingLevel.ERROR)), hitsOf("R018"));
    }

    @Test
    void r021CicsResponseUnchecked() {
        assertEquals(Set.of(hit("SYK008.cbl", 38, FindingLevel.ERROR)), hitsOf("R021"));
    }

    @Test
    void r022CicsReturnMissing() {
        assertEquals(Set.of(hit("SYK009.cbl", 21, FindingLevel.WARNING)), hitsOf("R022"));
    }

    @Test
    void r031UndefinedBmsMapReference() {
        assertEquals(Set.of(hit("SYK008.cbl", 56, FindingLevel.ERROR)), hitsOf("R031"));
    }

    @Test
    void fixtureOnlyRulesDetectNothingInSamples() {
        // Samples contain no defects corresponding to these 6 rules. Whether they detect
        // anything is asserted by the synthetic fixture tests; here we only confirm no
        // false positives occur in samples.
        for (String ruleId : List.of("R009", "R010", "R014", "R019", "R029", "R030")) {
            assertEquals(Set.of(), hitsOf(ruleId), ruleId + " は samples で0件であること");
        }
    }
}
