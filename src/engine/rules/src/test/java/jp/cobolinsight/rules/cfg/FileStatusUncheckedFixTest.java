package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FixSuggestion;
import jp.cobolinsight.engineapi.finding.TextEdit;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.rules.FixApplyChecks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R017 の FixProducer 検証。FILE STATUS を検査しない I/O 文の直後へ、FD の STATUS 変数を
 * 判定する IF 文を挿入する TextEdit を返すことを確認する。STATUS 変数・FD 名はルールと同じ
 * SELECT/FD 解決で再取得する。終止ピリオドは I/O 文が文を閉じている場合にのみ付き、囲む文の
 * 途中では END-IF だけで閉じる。
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

        FixSuggestion suggestion = new FileStatusUncheckedRule().fixProducer().orElseThrow()
                .produce(finding, context).orElseThrow(() -> new AssertionError("R017 の修正案が返ること"));
        assertEquals(1, suggestion.edits().size());
        TextEdit edit = suggestion.edits().get(0);
        // END-READ は88行。その直後(89行先頭)へ空範囲挿入する。
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
        // IF の THEN 節末尾にある WRITE(終止ピリオド無し)の直後へは、ピリオドを付けない
        // IF … END-IF を挿入する。END-IF で閉じるため外側の ELSE・END-IF の結合は変わらない。
        AnalysisContext context = CfgFixtures.samples();
        String file = CfgFixtures.samplesFile("SYK001.cbl");
        Finding finding = finding(context, file, 126);

        FixSuggestion suggestion = new FileStatusUncheckedRule().fixProducer().orElseThrow()
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
        // ELSE 節末尾(次行が END-IF.)にある WRITE でも同様にピリオドを付けない。
        AnalysisContext context = CfgFixtures.samples();
        String file = CfgFixtures.samplesFile("SYK001.cbl");
        Finding finding = finding(context, file, 130);

        FixSuggestion suggestion = new FileStatusUncheckedRule().fixProducer().orElseThrow()
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
        // END-WRITE で閉じるが終止ピリオドを持たない WRITE の直後でも、ピリオドを付けない。
        AnalysisContext context = CfgFixtures.samples();
        String file = CfgFixtures.samplesFile("SYK002.cbl");
        Finding finding = finding(context, file, 107);

        FixSuggestion suggestion = new FileStatusUncheckedRule().fixProducer().orElseThrow()
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

        FixSuggestion suggestion = new FileStatusUncheckedRule().fixProducer().orElseThrow()
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
