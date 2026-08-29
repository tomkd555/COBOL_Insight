package jp.cobolinsight.frontend.bms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BmsErrorReportingTest {

    private static BmsParseResult parse(String... lines) {
        return new BmsSourceParser().parse(String.join("\n", lines) + "\n");
    }

    @Test
    void 閉じ括弧欠落を位置付きエラーとして報告し例外で落ちない() {
        BmsParseResult result = assertDoesNotThrow(() -> parse(
                "MYSET    DFHMSD TYPE=MAP",
                "MYMAP    DFHMDI SIZE=(24,80)",
                "FLD1     DFHMDF POS=(1,",
                "         DFHMSD TYPE=FINAL"));

        assertFalse(result.errors().isEmpty());
        assertTrue(result.errors().stream().anyMatch(e -> e.line() >= 3),
                "3行目以降の位置を持つエラーがある: " + result.errors());
    }

    @Test
    void 不正な文字を位置付きエラーとして報告する() {
        BmsParseResult result = assertDoesNotThrow(() -> parse(
                "MYSET    DFHMSD TYPE=MAP",
                "MYMAP    DFHMDI SIZE=(24,80)",
                "FLD1     DFHMDF POS=(1,2),LENGTH=!!"));

        assertFalse(result.errors().isEmpty());
        assertTrue(result.errors().stream().anyMatch(e -> e.line() == 3),
                "3行目の位置を持つエラーがある: " + result.errors());
    }

    @Test
    void マップセット外のDFHMDIとマップ外のDFHMDFを行番号付きで報告する() {
        BmsParseResult result = assertDoesNotThrow(() -> parse(
                "MYMAP    DFHMDI SIZE=(24,80)",
                "MYSET    DFHMSD TYPE=MAP",
                "FLD1     DFHMDF POS=(1,2),LENGTH=3"));

        assertEquals(2, result.errors().size());
        assertEquals(1, result.errors().get(0).line());
        assertEquals(3, result.errors().get(1).line());
        assertTrue(result.mapsets().get(0).maps().isEmpty());
    }
}
