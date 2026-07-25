package jp.cobolinsight.cli;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FixSuggestion;
import jp.cobolinsight.engineapi.finding.TextEdit;
import jp.cobolinsight.engineapi.pipeline.AnalysisServices;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * lint の SARIF が、FixProducer を持つルールの検出へ fixes(artifactChanges のソース範囲置換)を
 * 付すことの検証。FixProducer を持つルールの集合は
 * 実行時に {@link Rule#fixProducer()} へ問い合わせて求め、テスト側で固定値として持たない。
 */
class LintSarifFixesTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

    private static LintRunner.Result result;

    @BeforeAll
    static void lintSamples() {
        result = LintRunner.run(new LintRunner.Options(SAMPLES,
                List.of(SAMPLES.resolve("copybook")), Map.of(), Set.of()));
    }

    /** lint が実行するルールのうち FixProducer を持つものの id(実行時問い合わせ)。 */
    private static Set<String> ruleIdsWithFixProducer() {
        AnalysisServices services = AnalysisServices.load();
        return Stream.of(services.rules(AnalysisPhase.SYNTAX),
                        services.rules(AnalysisPhase.CONTROL_FLOW),
                        services.rules(AnalysisPhase.DATA_FLOW))
                .flatMap(List::stream)
                .filter(rule -> rule.id().startsWith("R"))
                .filter(rule -> rule.fixProducer().isPresent())
                .map(Rule::id)
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
