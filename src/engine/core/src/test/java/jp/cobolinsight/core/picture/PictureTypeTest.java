package jp.cobolinsight.core.picture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PictureTypeTest {

    @Test
    void packedDecimalSignedWithScale() {
        PictureType t = PictureType.parse("S9(09)V99", "COMP-3");
        assertTrue(t.signed());
        assertEquals(9, t.integerDigits());
        assertEquals(2, t.fractionDigits());
        assertEquals(11, t.totalDigits());
        assertTrue(t.isNumeric());
        assertEquals(PictureCategory.NUMERIC, t.category());
        // COMP-3 は1バイトに2桁を詰め最下位に符号を置くため、総桁数11に符号1桁を足した12桁で6バイトになる
        assertEquals(6, t.byteLength());
    }

    @Test
    void displayUnsignedInteger() {
        PictureType t = PictureType.parse("9(06)", "DISPLAY");
        assertFalse(t.signed());
        assertEquals(6, t.integerDigits());
        assertEquals(0, t.fractionDigits());
        assertEquals(6, t.totalDigits());
        assertTrue(t.isNumeric());
        assertEquals(6, t.byteLength());
    }

    @Test
    void missingUsageDefaultsToDisplay() {
        assertEquals(Usage.DISPLAY, PictureType.parse("9(06)", null).usage());
        assertEquals(6, PictureType.parse("9(06)", "").byteLength());
    }

    @Test
    void alphanumericCountsCharacters() {
        PictureType t = PictureType.parse("X(20)", "DISPLAY");
        assertEquals(PictureCategory.ALPHANUMERIC, t.category());
        assertFalse(t.isNumeric());
        assertFalse(t.signed());
        assertEquals(20, t.byteLength());
    }

    @Test
    void alphabeticIsClassifiedAndSized() {
        PictureType t = PictureType.parse("A(10)", "DISPLAY");
        assertEquals(PictureCategory.ALPHABETIC, t.category());
        assertFalse(t.isNumeric());
        assertEquals(10, t.byteLength());
    }

    @Test
    void binaryByDigitBands() {
        // COMP のバイト長は桁数帯で決まる。1〜4桁は2バイト、5〜9桁は4バイト、10〜18桁は8バイトとする
        assertEquals(2, PictureType.parse("9(4)", "COMP").byteLength());
        assertEquals(4, PictureType.parse("9(9)", "COMP").byteLength());
        assertEquals(8, PictureType.parse("9(18)", "COMP").byteLength());
    }

    @Test
    void impliedDecimalSingleDigits() {
        PictureType t = PictureType.parse("9V9", "DISPLAY");
        assertEquals(1, t.integerDigits());
        assertEquals(1, t.fractionDigits());
        assertEquals(2, t.totalDigits());
        assertTrue(t.isNumeric());
    }

    @Test
    void packedDecimalAliasesNormalizeAndSize() {
        assertEquals(Usage.PACKED_DECIMAL, Usage.normalize("COMP-3"));
        assertEquals(Usage.PACKED_DECIMAL, Usage.normalize("COMPUTATIONAL-3"));
        assertEquals(Usage.PACKED_DECIMAL, Usage.normalize("PACKED-DECIMAL"));
        assertEquals(6, PictureType.parse("S9(09)V99", "PACKED-DECIMAL").byteLength());
        assertEquals(6, PictureType.parse("S9(09)V99", "COMPUTATIONAL-3").byteLength());
    }

    @Test
    void binaryAliasesNormalizeAndSize() {
        assertEquals(Usage.BINARY, Usage.normalize("BINARY"));
        assertEquals(Usage.BINARY, Usage.normalize("COMP"));
        assertEquals(Usage.BINARY, Usage.normalize("COMP-4"));
        assertEquals(Usage.BINARY, Usage.normalize("COMP-5"));
        assertEquals(Usage.BINARY, Usage.normalize("COMPUTATIONAL-4"));
        assertEquals(2, PictureType.parse("S9(4)", "COMP-4").byteLength());
        assertEquals(4, PictureType.parse("S9(9)", "COMP").byteLength());
    }

    @Test
    void blankPictureIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PictureType.parse("   ", "DISPLAY"));
    }
}
