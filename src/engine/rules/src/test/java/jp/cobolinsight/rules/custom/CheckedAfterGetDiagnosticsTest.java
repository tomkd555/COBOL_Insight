package jp.cobolinsight.rules.custom;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.cfg.CfgFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@code checked-after} rule over {@code EXEC SQL} has to read a following GET DIAGNOSTICS as the
 * check, the way the built-in R018 does; the acceptance test that holds the declarative form to
 * R018 line for line over {@code samples/} rests on it. The recognition is tied to the subject verb,
 * so a rule written over READ still reports: after a READ the diagnostics area says nothing.
 */
class CheckedAfterGetDiagnosticsTest {

    /** R018 as a checked-after rule: the boundary is the next EXEC SQL. */
    private static Rule sqlRule() {
        return CustomRules.of(Map.of(
                "id", "U018",
                "name", "SQLCODE未検査",
                "message", "SQL 文の実行後、SQLCODE を検査していない",
                "commands", List.of("LINT"),
                "targets", List.of("COBOL"),
                "match", Map.of(
                        "kind", "checked-after",
                        "after", Map.of("verb", "EXEC SQL",
                                "textRegex", "^EXEC SQL (INSERT|UPDATE|DELETE|SELECT|FETCH)\\b"),
                        "checks", Map.of("dataItem", List.of("SQLCODE", "SQLSTATE")),
                        "scope", "untilNextMatchingStatement")));
    }

    /** The same shape over a record-access verb, whose status a GET DIAGNOSTICS does not carry. */
    private static Rule readRule() {
        return CustomRules.of(Map.of(
                "id", "U017",
                "name", "入出力状態未検査",
                "message", "READ の後、入出力状態を検査していない",
                "commands", List.of("LINT"),
                "targets", List.of("COBOL"),
                "match", Map.of(
                        "kind", "checked-after",
                        "after", Map.of("verb", "READ"),
                        "checks", Map.of("dataItem", List.of("WS-IN-STATUS")),
                        "scope", "untilProgramEnd")));
    }

    @Test
    void aGetDiagnosticsAfterTheUpdateIsTheCheck(@TempDir Path dir) {
        assertEquals(List.of(), evaluate(sqlRule(), dir, "FIXU18A", """
                           EXEC SQL
                               UPDATE MYTAB SET COL1 = 1
                           END-EXEC
                           EXEC SQL
                               GET DIAGNOSTICS :WS-ROWS = ROW_COUNT
                           END-EXEC
                """));
    }

    @Test
    void theSameUpdateWithoutItIsReported(@TempDir Path dir) {
        List<Finding> findings = evaluate(sqlRule(), dir, "FIXU18B", """
                           EXEC SQL
                               UPDATE MYTAB SET COL1 = 1
                           END-EXEC
                           DISPLAY 'UPDATE DONE'
                """);
        assertEquals(1, findings.size(), () -> findings.toString());
        assertEquals("U018", findings.get(0).ruleId());
    }

    @Test
    void aGetDiagnosticsAfterAReadIsNotTheCheck(@TempDir Path dir) {
        List<Finding> findings = evaluate(readRule(), dir, "FIXU17A", """
                           READ IN-FILE
                               AT END
                                   CONTINUE
                           END-READ
                           EXEC SQL
                               GET DIAGNOSTICS :WS-ROWS = ROW_COUNT
                           END-EXEC
                """);
        assertEquals(1, findings.size(), () -> findings.toString());
        assertEquals("U017", findings.get(0).ruleId());
    }

    /** The body wrapped in the smallest program that parses, with its CFG built. */
    private static List<Finding> evaluate(Rule rule, Path dir, String programId, String body) {
        String text = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. %s.
                       ENVIRONMENT DIVISION.
                       INPUT-OUTPUT SECTION.
                       FILE-CONTROL.
                           SELECT IN-FILE ASSIGN TO INFILE
                                  ORGANIZATION IS SEQUENTIAL
                                  FILE STATUS  IS WS-IN-STATUS.
                       DATA DIVISION.
                       FILE SECTION.
                       FD  IN-FILE
                           RECORDING MODE IS F.
                       01  IN-REC                      PIC X(80).
                       WORKING-STORAGE SECTION.
                           EXEC SQL INCLUDE SQLCA END-EXEC.
                       01  WS-IN-STATUS                PIC X(02) VALUE SPACE.
                       01  WS-ROWS                     PIC S9(09) COMP VALUE ZERO.
                       PROCEDURE DIVISION.
                       0000-MAIN.
                %s           GOBACK.
                """.formatted(programId, body);
        CobolSemanticModel model = CfgFixtures.parse(dir, programId + ".cbl", text);
        AnalysisContext context =
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text));
        return rule.evaluate(context);
    }
}
