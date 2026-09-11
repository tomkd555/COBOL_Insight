package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.rules.RuleSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The declarative {@code checked-after} rule kind against the built-in rule it was modelled on.
 *
 * <p>R018 walks the control flow graph in Java to find SQL that changes data or reads a row and
 * whose status is never examined. The same check written as a {@code custom} entry of
 * {@code rules.json} has to land on exactly the same lines of the sample corpus; if it does not,
 * the declarative form is not a faithful way to express a rule of that shape and authors would
 * be misled by it. (The samples have no WHENEVER, so the built-in rule's WHENEVER handling does
 * not separate the two here.)
 */
class CheckedAfterRuleAcceptanceTest {

    private static final Path SAMPLES =
            Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    /** R018 expressed with match.kind = checked-after: the boundary is the next EXEC SQL. */
    private static final String RULES = """
            {
              "version": 2,
              "custom": [
                {
                  "id": "U018",
                  "name": "SQLCODE/SQLSTATE未検査",
                  "category": "例外処理",
                  "severity": "HIGH",
                  "commands": ["LINT"],
                  "targets": ["COBOL"],
                  "match": {
                    "kind": "checked-after",
                    "after": {
                      "verb": "EXEC SQL",
                      "textRegex": "^EXEC SQL (INSERT|UPDATE|DELETE|SELECT|FETCH)\\\\b"
                    },
                    "checks": { "dataItem": ["SQLCODE", "SQLSTATE"] },
                    "scope": "untilNextMatchingStatement"
                  },
                  "message": "SQL 文の実行後、SQLCODE・SQLSTATE を検査していない"
                }
              ]
            }
            """;

    private static List<String> locationsOf(List<Finding> findings, String ruleId) {
        return findings.stream()
                .filter(finding -> finding.ruleId().equals(ruleId))
                .map(finding -> finding.location().file().replace('\\', '/')
                        + ":" + finding.location().line())
                .sorted()
                .toList();
    }

    @Test
    void declarativeRuleFindsTheSameSitesAsTheBuiltinOne(@TempDir Path dir) throws IOException {
        Path rulesFile = dir.resolve("rules.json");
        Files.writeString(rulesFile, RULES, StandardCharsets.UTF_8);

        LintRunner.Result result = LintRunner.run(new LintRunner.Options(SAMPLES,
                List.of(SAMPLES.resolve("copybook")), Map.of(), RuleSet.load(rulesFile)));

        assertEquals(List.of("cobol/SYK006.cbl:119", "cobol/SYK007.cbl:73", "cobol/SYK007.cbl:89"),
                locationsOf(result.findings(), "U018"));
        assertEquals(locationsOf(result.findings(), "R018"),
                locationsOf(result.findings(), "U018"));
    }
}
