package jp.cobolinsight.core.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which range holds which: by file, then line, then column, boundaries included. */
class SourceRangeTest {

    private static final SourceRange OUTER = range("PGM.cbl", 5, 12, 5, 40);

    @Test
    void onOneLineTheColumnsDecide() {
        assertTrue(OUTER.contains(range("PGM.cbl", 5, 20, 5, 30)));
        assertFalse(OUTER.contains(range("PGM.cbl", 5, 10, 5, 20)), "starts before");
        assertFalse(OUTER.contains(range("PGM.cbl", 5, 30, 5, 41)), "ends after");
    }

    @Test
    void anotherFileWithTheSameLinesIsNotHeld() {
        assertFalse(OUTER.contains(range("ONE.cpy", 5, 20, 5, 30)));
    }

    @Test
    void equalBoundariesAreHeld() {
        assertTrue(OUTER.contains(OUTER));
        assertTrue(OUTER.contains(range("PGM.cbl", 5, 12, 5, 12)));
        assertTrue(OUTER.contains(range("PGM.cbl", 5, 40, 5, 40)));
    }

    private static SourceRange range(String file, int line, int column, int endLine,
            int endColumn) {
        return new SourceRange(
                new SourcePosition(file, line, column, SourcePosition.UNKNOWN_BYTE_OFFSET),
                new SourcePosition(file, endLine, endColumn, SourcePosition.UNKNOWN_BYTE_OFFSET));
    }
}
