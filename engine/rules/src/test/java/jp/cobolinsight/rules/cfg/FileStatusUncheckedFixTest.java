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
 * SELECT/FD 解決で再取得する。
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
    void doesNotFixInBlockWriteInSyk001() {
        // IF/ELSE ブロックの途中にある WRITE(終止ピリオド無し)は検出はするが、直後へピリオド終端の
        // 検査文を挿入すると囲む IF を壊すため、修正案を出さない。
        AnalysisContext context = CfgFixtures.samples();
        String file = CfgFixtures.samplesFile("SYK001.cbl");
        Finding finding = finding(context, file, 126);

        assertTrue(new FileStatusUncheckedRule().fixProducer().orElseThrow()
                        .produce(finding, context).isEmpty(),
                "ブロック途中の WRITE には修正案を出さないこと");
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
