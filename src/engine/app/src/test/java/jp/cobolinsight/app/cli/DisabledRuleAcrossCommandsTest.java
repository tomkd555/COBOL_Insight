package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.SourceSet;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.rules.RuleSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Disabling a rule in {@code rules.json} has to hold for every subcommand, not only for the one
 * whose test happened to be written. Each subcommand asks the same {@link RuleSet} for its rules,
 * so the filter cannot drift; this test is what keeps that true as subcommands are added.
 *
 * <p>The subject is a custom rule written so that it certainly fires on the sample corpus — a rule
 * that never fires would make the disabled run pass for the wrong reason. {@code fix} is the one
 * command it cannot cover, because only a rule carrying a fix runs there; that case takes R004,
 * which does.
 */
class DisabledRuleAcrossCommandsTest {

    private static final Path SAMPLES =
            Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    private static final List<Path> COPYBOOKS = List.of(SAMPLES.resolve("copybook"));

    /** Matches the PROGRAM-ID line every COBOL source has, and declares every command. */
    private static final String CUSTOM_RULE = """
                {
                  "id": "U900",
                  "name": "常に一致する検査",
                  "category": "試験",
                  "commands": ["LINT", "SQL_LINT", "REPORT", "FIX", "SCAN"],
                  "targets": ["COBOL"],
                  "match": { "kind": "line", "regex": "PROGRAM-ID" },
                  "message": "PROGRAM-ID 行に一致した"
                }
            """;

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @EnumSource(Command.class)
    void disabledRuleReportsNothingUnderEveryCommand(Command command) throws IOException {
        String ruleId = subjectOf(command);

        assertTrue(reportsOf(command, ruleSet(command, null)) > 0,
                ruleId + " must report something under " + command
                        + ", or the disabled run proves nothing");

        RuleSet disabled = ruleSet(command, ruleId);
        assertTrue(disabled.forCommand(command).stream()
                        .noneMatch(rule -> rule.meta().id().equals(ruleId)),
                ruleId + " must be gone from the rules " + command + " asks for");
        assertEquals(0, reportsOf(command, disabled));
    }

    /** Only a rule with a fix runs under fix, so that command needs a built-in subject. */
    private static String subjectOf(Command command) {
        return command == Command.FIX ? "R004" : "U900";
    }

    private RuleSet ruleSet(Command command, String disabledId) throws IOException {
        Path file = tempDir.resolve(command + (disabledId == null ? "-on" : "-off") + ".json");
        Files.writeString(file, """
                {
                  "version": 2,
                  %s
                  "custom": [
                %s
                  ]
                }
                """.formatted(disabledId == null ? ""
                        : "\"rules\": { \"" + disabledId + "\": { \"enabled\": false } },",
                        CUSTOM_RULE),
                StandardCharsets.UTF_8);
        return RuleSet.load(file);
    }

    /** How many times the subject rule is reported by the subcommand's own entry point. */
    private long reportsOf(Command command, RuleSet ruleSet) {
        String ruleId = subjectOf(command);
        return switch (command) {
            case LINT -> count(LintRunner.run(new LintRunner.Options(
                    SAMPLES, COPYBOOKS, Map.of(), ruleSet)).findings(), ruleId);
            case SQL_LINT -> count(SqlAdviseRunner.run(new SqlAdviseRunner.Options(
                    SAMPLES, COPYBOOKS, Map.of(), ruleSet)).findings(), ruleId);
            case REPORT -> count(ReportRunner.run(new ReportRunner.Options(
                    SAMPLES, scannedDatabase(), COPYBOOKS, Map.of(), ruleSet)).lintFindings(),
                    ruleId);
            // fix preview: the findings a fix is produced from, before the diff is rendered.
            case FIX -> {
                SourceSet s = Pipelines.fix(SAMPLES, COPYBOOKS, Map.of(), ruleSet);
                yield count(s.ruleFindings(Command.FIX), ruleId);
            }
            // scan keeps no rule finding of its own; what a rule changes there is the exit code,
            // which is 0 for this corpus until a rule reports against it.
            case SCAN -> Pipelines.scan(SAMPLES, tempDir.resolve(ruleSet.hashCode() + ".db"),
                    COPYBOOKS, Map.of(), ruleSet).summary().exitCode();
        };
    }

    private static long count(List<Finding> findings, String ruleId) {
        return findings.stream().filter(finding -> finding.ruleId().equals(ruleId)).count();
    }

    private Path scannedDatabase() {
        Path databaseFile = tempDir.resolve("report.db");
        if (!Files.isRegularFile(databaseFile)) {
            Pipelines.scan(SAMPLES, databaseFile, COPYBOOKS, Map.of());
        }
        return databaseFile;
    }

    /** The corpus scans clean, so the exit code is a usable signal that a scan rule fired. */
    @Test
    void theCorpusScansCleanWithoutCustomRules() {
        assertEquals(0, Pipelines.scan(SAMPLES, tempDir.resolve("clean.db"), COPYBOOKS, Map.of())
                .summary().exitCode());
    }
}
