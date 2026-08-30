package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FixSuggestion;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.fix.ByteSpliceApplier;
import jp.cobolinsight.rules.FixApplyChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FixProducer verification for R004. Confirms that, for an arithmetic statement lacking an
 * ON SIZE ERROR clause, it returns a TextEdit that inserts an ON SIZE ERROR handler while
 * relocating the terminating period to after END-COMPUTE.
 */
class OnSizeErrorMissingFixTest {

    @TempDir
    Path tempDir;

    @Test
    void producesOnSizeErrorEditForSyk007Compute() {
        AnalysisContext context = DataFlowFixtures.samples();
        String file = DataFlowFixtures.samplesFile("SYK007.cbl");
        OnSizeErrorMissingRule rule = new OnSizeErrorMissingRule();
        Finding finding = rule.evaluate(context).stream()
                .filter(f -> f.location().file().equals(file) && f.location().line() == 79)
                .findFirst()
                .orElseThrow(() -> new AssertionError("SYK007 の R004 検出が前提"));

        FixSuggestion suggestion = rule.fix().orElseThrow().produce(finding, context)
                .orElseThrow(() -> new AssertionError("R004 の修正案が返ること"));
        assertEquals(1, suggestion.edits().size());
        TextEdit edit = suggestion.edits().get(0);

        // The arithmetic statement's content ends at line 80, column 47 (just before the
        // terminating period). An ON SIZE ERROR clause is inserted here as a zero-width range.
        // Since the original terminating period stays right after the insertion, it ends up
        // after END-COMPUTE.
        assertEquals(80, edit.range().start().line());
        assertEquals(47, edit.range().start().column());
        assertEquals(80, edit.range().end().line());
        assertEquals(47, edit.range().end().column());
        assertEquals("\n           ON SIZE ERROR DISPLAY 'SIZE ERROR: WS-引当率' END-COMPUTE",
                edit.replacement());

        // The fixed source obtained by applying the generated edit to the original must reparse
        // (column rules preserved).
        assertTrue(FixApplyChecks.applyAndReparse(file, suggestion.edits(),
                        List.of(DataFlowFixtures.SAMPLES.resolve("copybook"))).success(),
                "R004 修正後ソースが再パースできること");
    }

    @Test
    void keepsMovedPeriodInsideColumn72WhenReceiverNameIsOneByteLonger() {
        // The insertion line arising from samples is exactly 72 bytes, leaving no room to move
        // the original terminating period onto the same line. Asserts that even with input
        // whose receiver name is one byte longer (the inserted statement is 61 bytes, equal to
        // the limit that fits in area B), the result does not spill into the identification
        // area at column 73.
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. FIX004P.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-引当率X  PIC 9(03).",
                "       01  WS-A        PIC 9(05).",
                "       01  WS-B        PIC 9(05).",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           COMPUTE WS-引当率X = WS-A * WS-B.",
                "           STOP RUN.",
                "");
        var model = DataFlowFixtures.parse(tempDir, "FIX004P.cbl", text);
        AnalysisContext context =
                DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text));
        OnSizeErrorMissingRule rule = new OnSizeErrorMissingRule();
        Finding finding = rule.evaluate(context).get(0);
        FixSuggestion suggestion = rule.fix().orElseThrow().produce(finding, context)
                .orElseThrow();
        assertEquals(61, "ON SIZE ERROR DISPLAY 'SIZE ERROR: WS-引当率X' END-COMPUTE"
                .getBytes(StandardCharsets.UTF_8).length, "挿入文はB領域の予算61バイトに等しい");

        byte[] fixed;
        try {
            fixed = new ByteSpliceApplier().apply(Path.of(model.sourceFile()), suggestion.edits());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String line : new String(fixed, StandardCharsets.UTF_8).split("\n", -1)) {
            assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 72,
                    "識別欄(73-80桁)へ食い込まないこと: [" + line + "]");
        }
    }

    @Test
    void producesEndVerbMatchingArithmeticVerb() {
        // Confirms with a synthetic fixture that even the GIVING form (END-MULTIPLY) closes with
        // the END clause matching the verb.
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. FIX004M.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-A    PIC 9(05).",
                "       01  WS-B    PIC 9(05).",
                "       01  WS-R    PIC 9(03).",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           MULTIPLY WS-A BY WS-B GIVING WS-R",
                "           DISPLAY WS-R",
                "           STOP RUN.",
                "");
        var model = DataFlowFixtures.parse(tempDir, "FIX004M.cbl", text);
        AnalysisContext context =
                DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text));
        OnSizeErrorMissingRule rule = new OnSizeErrorMissingRule();
        Finding finding = rule.evaluate(context).get(0);
        FixSuggestion suggestion = rule.fix().orElseThrow().produce(finding, context)
                .orElseThrow();
        String replacement = suggestion.edits().get(0).replacement();
        assertTrue(replacement.contains("END-MULTIPLY"), replacement);
        assertTrue(replacement.contains("ON SIZE ERROR DISPLAY 'SIZE ERROR: WS-R'"), replacement);
    }
}
