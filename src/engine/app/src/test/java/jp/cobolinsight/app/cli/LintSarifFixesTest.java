package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FixSuggestion;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.rules.BuiltinRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that lint's SARIF attaches fixes (artifactChanges source-range replacements) to
 * detections from rules that have a FixProducer. The set of rules with a FixProducer is obtained
 * at runtime by querying {@link Rule#fix()}, and is not hardcoded on the test side.
 */
class LintSarifFixesTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    private static LintRunner.Result result;

    @BeforeAll
    static void lintSamples() {
        result = LintRunner.run(new LintRunner.Options(SAMPLES,
                List.of(SAMPLES.resolve("copybook")), Map.of()));
    }

    /** The ids of the lint-run rules that have a FixProducer (queried at runtime). */
    private static Set<String> ruleIdsWithFixProducer() {
        return BuiltinRules.all().stream()
                .filter(rule -> rule.meta().id().startsWith("R"))
                .filter(rule -> rule.fix().isPresent())
                .map(rule -> rule.meta().id())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static List<Finding> withFixes() {
        return result.findings().stream().filter(f -> !f.fixes().isEmpty()).toList();
    }

    @Test
    void findingsOfRulesWithFixProducerCarryFixSuggestions() {
        Set<String> producers = ruleIdsWithFixProducer();
        assertFalse(producers.isEmpty(), "FixProducer を持つルールが1件以上登録されていること");

        Set<String> fixedRuleIds = withFixes().stream().map(Finding::ruleId)
                .collect(Collectors.toCollection(TreeSet::new));
        assertFalse(fixedRuleIds.isEmpty(),
                () -> "samples の lint で修正案付き finding が出ること(FixProducer 保持ルール: "
                        + producers + ")");
        assertTrue(producers.containsAll(fixedRuleIds),
                () -> "修正案は FixProducer を持つルールの検出にのみ付くこと: " + fixedRuleIds);
    }

    @Test
    void fixEditRangesAreRelativizedLikeTheFindingLocation() {
        for (Finding finding : withFixes()) {
            for (FixSuggestion fix : finding.fixes()) {
                for (TextEdit edit : fix.edits()) {
                    String file = edit.range().start().file();
                    assertFalse(Path.of(file).isAbsolute(),
                            () -> "編集範囲のファイルも入力フォルダ相対であること: " + file);
                }
            }
        }
    }

    @Test
    void sarifCarriesArtifactChangesForEveryFixSuggestion() {
        String sarif = result.sarifJson();
        long fixCount = sarif.split("\"artifactChanges\":", -1).length - 1;

        assertEquals(withFixes().size(), fixCount,
                "修正案付き finding の件数だけ artifactChanges が出ること");
        assertTrue(sarif.contains("\"replacements\":[{\"deletedRegion\":"),
                "replacements が deletedRegion を持つこと");
    }
}
