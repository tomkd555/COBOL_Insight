package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FixSuggestion;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.FixApplyChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R018 FixProducer verification. Confirms it returns a TextEdit inserting an IF statement
 * that tests SQLCODE, right after the EXEC SQL of a data-changing DML that does not check
 * SQLCODE (the line after the END-EXEC line). A terminating period is added only when
 * END-EXEC closes the sentence; in the middle of an enclosing statement it closes with
 * END-IF alone.
 */
class SqlCodeUncheckedFixTest {

    @TempDir
    Path tempDir;

    private static final String CHECK =
            "           IF SQLCODE NOT = 0 DISPLAY 'SQL ERROR: ' SQLCODE END-IF.\n";

    private static Finding finding(AnalysisContext context, String file, int line) {
        return new SqlCodeUncheckedRule().evaluate(context).stream()
                .filter(f -> f.location().file().equals(file) && f.location().line() == line)
                .findFirst()
                .orElseThrow(() -> new AssertionError(file + ":" + line + " の R018 検出が前提"));
    }

    @Test
    void insertsSqlCodeCheckAfterUpdateInSyk006() {
        AnalysisContext context = CfgFixtures.samples();
        String file = CfgFixtures.samplesFile("SYK006.cbl");
        Finding finding = finding(context, file, 119);

        FixSuggestion suggestion = new SqlCodeUncheckedRule().fix().orElseThrow()
                .produce(finding, context).orElseThrow(() -> new AssertionError("R018 の修正案が返ること"));
        assertEquals(1, suggestion.edits().size());
        TextEdit edit = suggestion.edits().get(0);
        // END-EXEC is line 119. Insert an empty-range edit right after it (start of line 120).
        assertEquals(120, edit.range().start().line());
        assertEquals(1, edit.range().start().column());
        assertEquals(120, edit.range().end().line());
        assertEquals(1, edit.range().end().column());
        assertEquals(CHECK, edit.replacement());

        assertTrue(FixApplyChecks.applyAndReparse(file, suggestion.edits(),
                        List.of(CfgFixtures.SAMPLES.resolve("copybook"))).success(),
                "R018(SYK006) 修正後ソースが再パースできること");
    }

    @Test
    void insertsSqlCodeCheckAfterUpdateInSyk007() {
        AnalysisContext context = CfgFixtures.samples();
        String file = CfgFixtures.samplesFile("SYK007.cbl");
        Finding finding = finding(context, file, 89);

        FixSuggestion suggestion = new SqlCodeUncheckedRule().fix().orElseThrow()
                .produce(finding, context).orElseThrow();
        TextEdit edit = suggestion.edits().get(0);
        assertEquals(90, edit.range().start().line());
        assertEquals(1, edit.range().start().column());
        assertEquals(CHECK, edit.replacement());

        assertTrue(FixApplyChecks.applyAndReparse(file, suggestion.edits(),
                        List.of(CfgFixtures.SAMPLES.resolve("copybook"))).success(),
                "R018(SYK007) 修正後ソースが再パースできること");
    }

    @Test
    void insertsPeriodlessCheckForBlockDml() {
        // Right after an INSERT in the middle of an IF block (its END-EXEC has no
        // terminating period), insert a periodless IF ... END-IF. The outer IF remains
        // unbroken up to its own END-IF.
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. FIX018M.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                "       01  WS-FLAG PIC X.",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           IF WS-FLAG = 'Y'",
                "               EXEC SQL",
                "                   INSERT INTO T1 VALUES (1)",
                "               END-EXEC",
                "               DISPLAY 'DONE'",
                "           END-IF.",
                "");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018M.cbl", text);
        AnalysisContext context =
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text));
        SqlCodeUncheckedRule rule = new SqlCodeUncheckedRule();
        Finding finding = rule.evaluate(context).stream().findFirst()
                .orElseThrow(() -> new AssertionError("ブロック内 INSERT の R018 検出が前提"));
        FixSuggestion suggestion = rule.fix().orElseThrow().produce(finding, context)
                .orElseThrow(() -> new AssertionError("ブロック途中の INSERT にも修正案が返ること"));
        assertEquals("           IF SQLCODE NOT = 0 DISPLAY 'SQL ERROR: ' SQLCODE END-IF\n",
                suggestion.edits().get(0).replacement());

        assertTrue(FixApplyChecks.applyAndReparse(model.sourceFile(), suggestion.edits(), List.of())
                        .success(),
                "R018(ブロック途中) 修正後ソースが再パースできること");
    }
}
