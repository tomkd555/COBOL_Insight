package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FixSuggestion;
import jp.cobolinsight.engineapi.finding.TextEdit;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.fix.ByteSpliceApplier;
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
 * R004 の FixProducer 検証。ON SIZE ERROR 句を欠く算術文に対し、終止ピリオドを END-COMPUTE の
 * 後へ付け替えつつ ON SIZE ERROR ハンドラを挿入する TextEdit を返すことを確認する。
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

        FixSuggestion suggestion = rule.fixProducer().orElseThrow().produce(finding, context)
                .orElseThrow(() -> new AssertionError("R004 の修正案が返ること"));
        assertEquals(1, suggestion.edits().size());
        TextEdit edit = suggestion.edits().get(0);

        // 算術文の内容終端は80行47桁(終止ピリオドの直前)。ここへ空範囲で ON SIZE ERROR 句を
        // 挿入する。原本の終止ピリオドが挿入直後に残るため、END-COMPUTE の後にピリオドが回る。
        assertEquals(80, edit.range().start().line());
        assertEquals(47, edit.range().start().column());
        assertEquals(80, edit.range().end().line());
        assertEquals(47, edit.range().end().column());
        assertEquals("\n           ON SIZE ERROR DISPLAY 'SIZE ERROR: WS-引当率' END-COMPUTE",
                edit.replacement());

        // 生成した編集を原本へ適用した修正後ソースが再パースできること(桁規則が保たれること)。
        assertTrue(FixApplyChecks.applyAndReparse(file, suggestion.edits(),
                        List.of(DataFlowFixtures.SAMPLES.resolve("copybook"))).success(),
                "R004 修正後ソースが再パースできること");
    }

    @Test
    void keepsMovedPeriodInsideColumn72WhenReceiverNameIsOneByteLonger() {
        // samples が生む挿入行はちょうど72バイトで、原ソースの終止ピリオドを担ぎ込む余地が無い。
        // 受信名が1バイト長い入力(挿入文が61バイト=B領域の予算いっぱい)でも、73桁目の識別欄へ
        // 食い込まないことを表明する。
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
        FixSuggestion suggestion = rule.fixProducer().orElseThrow().produce(finding, context)
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
        // GIVING 形式(END-MULTIPLY)でも動詞に合わせた END-句で閉じることを合成fixtureで確認する。
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
        FixSuggestion suggestion = rule.fixProducer().orElseThrow().produce(finding, context)
                .orElseThrow();
        String replacement = suggestion.edits().get(0).replacement();
        assertTrue(replacement.contains("END-MULTIPLY"), replacement);
        assertTrue(replacement.contains("ON SIZE ERROR DISPLAY 'SIZE ERROR: WS-R'"), replacement);
    }
}
