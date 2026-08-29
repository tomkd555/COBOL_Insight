package jp.cobolinsight.core.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceModelTest {

    private static SourcePosition pos(String file, int line, int column) {
        return new SourcePosition(file, line, column, -1);
    }

    @Test
    void sourcePositionRejectsInvalidCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new SourcePosition("A.cbl", 0, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new SourcePosition("A.cbl", 1, 0, -1));
        // byteOffset は不明を表す -1 を許し、-2 以下は受け付けない
        assertThrows(IllegalArgumentException.class, () -> new SourcePosition("A.cbl", 1, 1, -2));
    }

    @Test
    void sourcePositionFileStartIsLineOneColumnOneWithUnknownByteOffset() {
        SourcePosition p = SourcePosition.fileStart("A.cbl");
        assertEquals("A.cbl", p.file());
        assertEquals(1, p.line());
        assertEquals(1, p.column());
        assertEquals(-1, p.byteOffset());
    }

    @Test
    void sourceRangeRequiresSameFileAndOrderedPositions() {
        assertThrows(IllegalArgumentException.class,
                () -> new SourceRange(pos("A.cbl", 1, 1), pos("B.cbl", 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceRange(pos("A.cbl", 2, 1), pos("A.cbl", 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceRange(pos("A.cbl", 1, 9), pos("A.cbl", 1, 8)));
        SourceRange r = new SourceRange(pos("A.cbl", 1, 8), pos("A.cbl", 1, 8));
        assertEquals(r.start(), r.end());
    }

    @Test
    void lineRangeRejectsInvalidRange() {
        assertThrows(IllegalArgumentException.class, () -> new LineRange(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new LineRange(5, 4));
        assertEquals(3, new LineRange(3, 3).startLine());
    }

    @Test
    void encodingInfoRejectsConfidenceOutsideZeroToOne() {
        assertThrows(IllegalArgumentException.class, () -> new EncodingInfo("UTF-8", -0.1, false, false));
        assertThrows(IllegalArgumentException.class, () -> new EncodingInfo("UTF-8", 1.1, false, false));
        EncodingInfo info = new EncodingInfo("x-IBM930", 0.8, true, true);
        assertTrue(info.manualOverride());
        assertTrue(info.soSiPresent());
    }

    @Test
    void decodedSourceMapsCharIndexToByteOffset() {
        EncodingInfo enc = new EncodingInfo("UTF-8", 0.9, false, false);
        DecodedSource src = new DecodedSource("A.cbl", "AB", new byte[]{65, 66}, new int[]{0, 1}, enc);
        assertEquals(0, src.byteOffsetAt(0));
        assertEquals(1, src.byteOffsetAt(1));
    }

    @Test
    void decodedSourceRejectsOffsetTableLengthMismatch() {
        EncodingInfo enc = new EncodingInfo("UTF-8", 0.9, false, false);
        assertThrows(IllegalArgumentException.class,
                () -> new DecodedSource("A.cbl", "AB", new byte[]{65, 66}, new int[]{0}, enc));
    }

    @Test
    void decodedSourceHasValueEqualityAndDefensiveArrayCopies() {
        EncodingInfo enc = new EncodingInfo("UTF-8", 0.9, false, false);
        DecodedSource a = new DecodedSource("A.cbl", "AB", new byte[]{65, 66}, new int[]{0, 1}, enc);
        DecodedSource b = new DecodedSource("A.cbl", "AB", new byte[]{65, 66}, new int[]{0, 1}, enc);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        byte[] leaked = a.originalBytes();
        leaked[0] = 0;
        assertEquals(a, b);
        int[] leakedOffsets = a.charByteOffsets();
        leakedOffsets[0] = 99;
        assertEquals(0, a.byteOffsetAt(0));
    }

    @Test
    void copyExpansionEntryValidatesLineNumbers() {
        assertThrows(IllegalArgumentException.class,
                () -> new CopyExpansionEntry(5, 4, "CPYA.cpy", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CopyExpansionEntry(0, 4, "CPYA.cpy", 1));
        CopyExpansionEntry e = new CopyExpansionEntry(10, 20, "CPYA.cpy", 3);
        assertEquals("CPYA.cpy", e.copybookPath());
        assertFalse(e.copybookPath().isEmpty());
    }

    @Test
    void copyInlineExpansionRejectsInvalidValuesAndCopiesLines() {
        assertThrows(IllegalArgumentException.class, () -> new ExpandedCopyLine(0, "X"));
        assertThrows(IllegalArgumentException.class,
                () -> new CopyInlineExpansion(0, "CPYA", "CPYA.cpy", java.util.List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new CopyInlineExpansion(1, " ", "CPYA.cpy", java.util.List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new CopyInlineExpansion(1, "CPYA", " ", java.util.List.of()));

        java.util.List<ExpandedCopyLine> lines =
                new java.util.ArrayList<>(java.util.List.of(new ExpandedCopyLine(7, "01 A.")));
        CopyInlineExpansion expansion = new CopyInlineExpansion(32, "CPYA", "CPYA.cpy", lines);
        lines.clear();
        assertEquals(1, expansion.lines().size(), "引数のリストを防御的に複製すること");
        assertEquals(7, expansion.lines().get(0).copybookLine());
    }

    @Test
    void positionedTokenHoldsTextAndRange() {
        SourceRange r = new SourceRange(pos("A.cbl", 3, 8), pos("A.cbl", 3, 12));
        PositionedToken t = new PositionedToken("MOVE", r);
        assertEquals("MOVE", t.text());
        assertEquals(3, t.range().start().line());
    }
}
