package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks lint findings against samples/expected-findings.tsv, the machine-readable form of
 * samples/expected-results.md section 2. R017 is path-sensitive and may report extra true
 * positives, so only containment of the expected set is asserted for it.
 */
class ExpectedFindingsAcceptanceTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();
    /** Rules whose detected set may be a superset of the expected set (see R017 note above). */
    private static final Set<String> SUPERSET_TOLERATED = Set.of("R017");

    record Expected(int no, String file, int line, String ruleId, FindingLevel level) {
        String fileLine() { return file + ":" + line; }
    }

    private static List<Expected> expected;
    private static LintRunner.Result result;

    @BeforeAll
    static void run() throws IOException {
        expected = Files.readAllLines(SAMPLES.resolve("expected-findings.tsv"), StandardCharsets.UTF_8).stream()
                .skip(1).filter(l -> !l.isBlank())
                .map(l -> l.split("\t"))
                .map(c -> new Expected(Integer.parseInt(c[0]), c[1], Integer.parseInt(c[2]), c[3],
                        FindingLevel.valueOf(c[4])))
                .toList();
        result = LintRunner.run(new LintRunner.Options(SAMPLES,
                List.of(SAMPLES.resolve("copybook")), Map.of()));
    }

    private static Set<String> detected(String ruleId) {
        return result.findings().stream().filter(f -> f.ruleId().equals(ruleId))
                .map(f -> f.location().file() + ":" + f.location().line())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void eighteenDefectsAreListed() {
        assertEquals(18, expected.size());
    }

    @Test
    void everyExpectedDefectIsDetectedAtItsLineWithItsLevel() {
        for (Expected e : expected) {
            List<Finding> hits = result.findings().stream()
                    .filter(f -> f.ruleId().equals(e.ruleId()))
                    .filter(f -> f.location().file().equals(e.file()) && f.location().line() == e.line())
                    .toList();
            assertEquals(1, hits.size(), () -> "No." + e.no() + " " + e.ruleId() + " " + e.fileLine()
                    + " must be detected exactly once: " + detected(e.ruleId()));
            assertEquals(e.level(), hits.get(0).level(), () -> "level of No." + e.no());
        }
    }

    @Test
    void rulesWithExpectedDefectsDetectNothingElse() {
        Map<String, Set<String>> byRule = expected.stream().collect(Collectors.groupingBy(Expected::ruleId,
                Collectors.mapping(Expected::fileLine, Collectors.toCollection(TreeSet::new))));
        byRule.forEach((ruleId, lines) -> {
            if (SUPERSET_TOLERATED.contains(ruleId)) {
                assertTrue(detected(ruleId).containsAll(lines), ruleId + " must contain every expected finding");
            } else {
                assertEquals(lines, detected(ruleId), ruleId + " must match the expected set exactly");
            }
        });
    }
}
