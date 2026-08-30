package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FixSuggestion;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.FixApplyChecks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R017 FixProducer verification. Confirms it returns a TextEdit inserting an IF statement,
 * right after an I/O statement that does not check FILE STATUS, that tests the FD's STATUS
 * variable. The STATUS variable and FD name are re-obtained with the same SELECT/FD
 * resolution the rule uses. A terminating period is added only when the I/O statement closes
 * the sentence; in the middle of an enclosing statement it closes with END-IF alone.
 */
class FileStatusUncheckedFixTest {

    private static Finding finding(AnalysisContext context, String file, int line) {
        return new FileStatusUncheckedRule().evaluate(context).stream()
                .filter(f -> f.location().file().equals(file) && f.location().line() == line)
                .findFirst()
                .orElseThrow(() -> new AssertionError(file + ":" + line + " の R017 検出が前提"));
    }

    @Test
    void insertsFileStatusCheckAfterReadInSyk001() {
        AnalysisContext context = CfgFixtures.samples();
        String file = CfgFixtures.samplesFile("SYK001.cbl");
        Finding finding = finding(context, file, 85);

        FixSuggestion suggestion = new FileStatusUncheckedRule().fix().orElseThrow()
                .produce(finding, context).orElseThrow(() -> new AssertionError("R017 の修正案が返ること"));
        assertEquals(1, suggestion.edits().size());
        TextEdit edit = suggestion.edits().get(0);
        // END-READ is line 88. Insert an empty-range edit right after it (start of line 89).
        assertEquals(89, edit.range().start().line());
        assertEquals(1, edit.range().start().column());
        assertEquals(89, edit.range().end().line());
        assertEquals(1, edit.range().end().column());
        assertEquals("           IF WS-ORDIN-STATUS NOT = '00' DISPLAY 'FILE ERROR: ORDIN '\n"
                + "           WS-ORDIN-STATUS END-IF.\n", edit.replacement());

        assertTrue(FixApplyChecks.applyAndReparse(file, suggestion.edits(),
                        List.of(CfgFixtures.SAMPLES.resolve("copybook"))).success(),
                "R017(READ) 修正後ソースが再パースできること");
    }

    @Test
    void insertsPeriodlessCheckForBlockWriteInSyk001() {
        // Right after a WRITE at the end of an IF's THEN clause (no terminating period),
        // insert a periodless IF ... END-IF. Since it closes with END-IF, the binding of
        // the outer ELSE/END-IF is unaffected.
        AnalysisContext context = CfgFixtures.samples();
        String file = CfgFixtures.samplesFile("SYK001.cbl");
        Finding finding = finding(context, file, 126);

        FixSuggestion suggestion = new FileStatusUncheckedRule().fix().orElseThrow()
                .produce(finding, context)
                .orElseThrow(() -> new AssertionError("ブロック途中の WRITE にも修正案が返ること"));
        TextEdit edit = suggestion.edits().get(0);
        assertEquals(127, edit.range().start().line());
        assertEquals("           IF WS-ORDERR-STATUS NOT = '00' DISPLAY 'FILE ERROR: ORDERR '\n"
                + "           WS-ORDERR-STATUS END-IF\n", edit.replacement());

        assertTrue(FixApplyChecks.applyAndReparse(file, suggestion.edits(),
                        List.of(CfgFixtures.SAMPLES.resolve("copybook"))).success(),
                "R017(THEN 節末尾の WRITE) 修正後ソースが再パースできること");
    }

    @Test
    void insertsPeriodlessCheckForBlockWriteBeforeEndIfInSyk001() {
        // The same applies for a WRITE at the end of an ELSE clause (next line is END-IF.):
        // no period is added.
        AnalysisContext context = CfgFixtures.samples();
        String file = CfgFixtures.samplesFile("SYK001.cbl");
        Finding finding = finding(context, file, 130);

        FixSuggestion suggestion = new FileStatusUncheckedRule().fix().orElseThrow()
                .produce(finding, context).orElseThrow();
        TextEdit edit = suggestion.edits().get(0);
        assertEquals(131, edit.range().start().line());
        assertEquals("           IF WS-ORDVALID-STATUS NOT = '00' DISPLAY\n"
                + "           'FILE ERROR: ORDVALID ' WS-ORDVALID-STATUS END-IF\n",
                edit.replacement());

        assertTrue(FixApplyChecks.applyAndReparse(file, suggestion.edits(),
                        List.of(CfgFixtures.SAMPLES.resolve("copybook"))).success(),
                "R017(ELSE 節末尾の WRITE) 修正後ソースが再パースできること");
    }

    @Test
    void insertsPeriodlessCheckAfterEndWriteInSyk002() {
        // Also no period is added right after a WRITE that closes with END-WRITE but has no
        // terminating period.
        AnalysisContext context = CfgFixtures.samples();
        String file = CfgFixtures.samplesFile("SYK002.cbl");
        Finding finding = finding(context, file, 107);

        FixSuggestion suggestion = new FileStatusUncheckedRule().fix().orElseThrow()
                .produce(finding, context).orElseThrow();
        TextEdit edit = suggestion.edits().get(0);
        assertEquals(111, edit.range().start().line());
        assertEquals("           IF WS-MASTER-STATUS NOT = '00' DISPLAY 'FILE ERROR: ORDMSTR '\n"
                + "           WS-MASTER-STATUS END-IF\n", edit.replacement());

        assertTrue(FixApplyChecks.applyAndReparse(file, suggestion.edits(),
                        List.of(CfgFixtures.SAMPLES.resolve("copybook"))).success(),
                "R017(END-WRITE 直後) 修正後ソースが再パースできること");
    }

    @Test
    void insertsFileStatusCheckAfterRewriteInSyk002() {
        AnalysisContext context = CfgFixtures.samples();
        String file = CfgFixtures.samplesFile("SYK002.cbl");
        Finding finding = finding(context, file, 130);

        FixSuggestion suggestion = new FileStatusUncheckedRule().fix().orElseThrow()
                .produce(finding, context).orElseThrow();
        TextEdit edit = suggestion.edits().get(0);
        assertEquals(131, edit.range().start().line());
        assertEquals(1, edit.range().start().column());
        assertEquals("           IF WS-MASTER-STATUS NOT = '00' DISPLAY 'FILE ERROR: ORDMSTR '\n"
                + "           WS-MASTER-STATUS END-IF.\n", edit.replacement());

        assertTrue(FixApplyChecks.applyAndReparse(file, suggestion.edits(),
                        List.of(CfgFixtures.SAMPLES.resolve("copybook"))).success(),
                "R017(REWRITE) 修正後ソースが再パースできること");
    }
}
