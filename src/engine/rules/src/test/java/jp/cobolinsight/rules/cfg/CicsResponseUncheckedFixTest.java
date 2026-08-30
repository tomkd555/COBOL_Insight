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
 * R021 FixProducer verification. Confirms it returns two TextEdits: one inserting a RESP
 * operand line right before END-EXEC, the other inserting a response-code check statement
 * right after it. The receiving variable is restricted to an elementary item in
 * WORKING-STORAGE equivalent to PIC S9(08) COMP whose name contains RESP; if none matches,
 * no fix suggestion is produced.
 */
class CicsResponseUncheckedFixTest {

    @TempDir
    Path tempDir;

    private static Finding finding(AnalysisContext context, String file, int line) {
        return new CicsResponseUncheckedRule().evaluate(context).stream()
                .filter(f -> f.location().file().equals(file) && f.location().line() == line)
                .findFirst()
                .orElseThrow(() -> new AssertionError(file + ":" + line + " の R021 検出が前提"));
    }

    @Test
    void insertsRespOperandAndJudgementAroundEndExecInSyk008() {
        AnalysisContext context = CfgFixtures.samples();
        String file = CfgFixtures.samplesFile("SYK008.cbl");
        Finding finding = finding(context, file, 38);

        FixSuggestion suggestion = new CicsResponseUncheckedRule().fix().orElseThrow()
                .produce(finding, context)
                .orElseThrow(() -> new AssertionError("R021 の修正案が返ること"));
        assertEquals(2, suggestion.edits().size());

        // END-EXEC is line 38. The operand line goes right before it (start of line 38).
        TextEdit operand = suggestion.edits().get(0);
        assertEquals(38, operand.range().start().line());
        assertEquals(1, operand.range().start().column());
        assertEquals(38, operand.range().end().line());
        assertEquals("           RESP(WS-RESPコード)\n", operand.replacement());

        // The check statement goes right after END-EXEC (start of line 39). Since END-EXEC
        // does not close the sentence, no terminating period is added; it closes with an
        // explicit END-IF only.
        TextEdit judgement = suggestion.edits().get(1);
        assertEquals(39, judgement.range().start().line());
        assertEquals(1, judgement.range().start().column());
        assertEquals("           IF WS-RESPコード NOT = 0 DISPLAY\n"
                        + "           'SYK008 RECEIVE MAPエラー RESP=' WS-RESPコード END-IF\n",
                judgement.replacement());

        assertTrue(FixApplyChecks.applyAndReparse(file, suggestion.edits(),
                        List.of(CfgFixtures.SAMPLES.resolve("copybook"))).success(),
                "R021 修正後ソースが再パースできること");
    }

    @Test
    void skipsFixWhenNoRespReceiverIsDeclared() {
        // If no item equivalent to PIC S9(08) COMP is available to receive RESP, detection
        // still proceeds but no fix suggestion is produced. No declaration is added to
        // WORKING-STORAGE.
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. FIX021C.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-MAP.",
                "           05  WS-F PIC X(08).",
                "       01  WS-RESP-TEXT PIC X(08).",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           EXEC CICS",
                "               RECEIVE MAP('MAP1')",
                "                   MAPSET('SET1')",
                "                   INTO(WS-MAP)",
                "           END-EXEC.",
                "");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX021C.cbl", text);
        AnalysisContext context =
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text));
        CicsResponseUncheckedRule rule = new CicsResponseUncheckedRule();
        Finding finding = rule.evaluate(context).get(0);

        assertTrue(rule.fix().orElseThrow().produce(finding, context).isEmpty(),
                "受け変数が無ければ修正案を出さないこと");
    }

    @Test
    void closesJudgementWithPeriodWhenEndExecEndsSentence() {
        // If END-EXEC closes the sentence with a terminating period, the check statement
        // also closes with a terminating period.
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. FIX021D.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-MAP.",
                "           05  WS-F PIC X(08).",
                "       01  WS-応答コード.",
                "           05  WS-RESPコード  PIC S9(08) COMP.",
                "           05  WS-RESP2コード PIC S9(08) COMP.",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           EXEC CICS",
                "               RECEIVE MAP('MAP1')",
                "                   MAPSET('SET1')",
                "                   INTO(WS-MAP)",
                "           END-EXEC.",
                "           GOBACK.",
                "");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX021D.cbl", text);
        AnalysisContext context =
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text));
        CicsResponseUncheckedRule rule = new CicsResponseUncheckedRule();
        Finding finding = rule.evaluate(context).get(0);

        FixSuggestion suggestion = rule.fix().orElseThrow().produce(finding, context)
                .orElseThrow();
        // A name containing RESP2 is not chosen as the RESP receiving variable.
        assertEquals("           RESP(WS-RESPコード)\n",
                suggestion.edits().get(0).replacement());
        assertTrue(suggestion.edits().get(1).replacement().contains("END-IF.\n"),
                suggestion.edits().get(1).replacement());

        assertTrue(FixApplyChecks.applyAndReparse(model.sourceFile(), suggestion.edits(), List.of())
                        .success(),
                "R021(ピリオド終端) 修正後ソースが再パースできること");
    }
}
