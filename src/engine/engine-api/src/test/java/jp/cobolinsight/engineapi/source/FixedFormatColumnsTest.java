package jp.cobolinsight.engineapi.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 固定形式の桁境界の正典({@link FixedFormatColumns})の検証。 */
class FixedFormatColumnsTest {

    @Test
    void areaBoundariesAreContiguousAndOneBased() {
        assertEquals(1, FixedFormatColumns.SEQUENCE_START);
        assertEquals(FixedFormatColumns.SEQUENCE_END + 1, FixedFormatColumns.INDICATOR_COLUMN);
        assertEquals(FixedFormatColumns.INDICATOR_COLUMN + 1, FixedFormatColumns.AREA_A_START);
        assertEquals(FixedFormatColumns.AREA_A_END + 1, FixedFormatColumns.AREA_B_START);
        assertEquals(FixedFormatColumns.CONTENT_END + 1,
                FixedFormatColumns.IDENTIFICATION_START);
    }

    @Test
    void indicatorReadsTheSeventhColumn() {
        assertEquals('*', FixedFormatColumns.indicator("      *注記行"));
        assertEquals('-', FixedFormatColumns.indicator("      - 継続行"));
        assertEquals(' ', FixedFormatColumns.indicator("       IDENTIFICATION DIVISION."));
        assertEquals(' ', FixedFormatColumns.indicator("短い"), "7桁に届かない行は空白扱い");
    }

    @Test
    void bodyDropsSequenceAreaAndIdentificationArea() {
        String line = "000100 IDENTIFICATION DIVISION.";
        assertEquals("IDENTIFICATION DIVISION.", FixedFormatColumns.body(line));

        String withIdentification = " ".repeat(7) + "A".repeat(65) + "IDENTIFY";
        assertEquals("A".repeat(65), FixedFormatColumns.body(withIdentification),
                "73桁目以降は本文に含めないこと");

        assertEquals("", FixedFormatColumns.body("      *"), "8桁目に届かない行は本文を持たない");
    }
}
