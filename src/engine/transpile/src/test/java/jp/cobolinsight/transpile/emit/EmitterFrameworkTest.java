package jp.cobolinsight.transpile.emit;

import jp.cobolinsight.core.linemap.LineMappingEntry;
import jp.cobolinsight.core.linemap.MappingKind;
import jp.cobolinsight.core.source.LineRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the line-tracking emitter framework (identifier sanitization, kind determination, line tracking, and stable sorting/numbering of the line map). */
class EmitterFrameworkTest {

    @Test
    void sanitizeReplacesHyphenAndKeepsJapanese() {
        assertEquals("SYK1_受注番号", Identifiers.sanitize("SYK1-受注番号"));
    }

    @Test
    void sanitizePrefixesUnderscoreWhenStartingWithDigit() {
        assertEquals("_1AB", Identifiers.sanitize("1AB"));
    }

    @Test
    void unquoteStripsSingleQuotes() {
        assertTrue(Literals.isQuoted("'1'"));
        assertEquals("1", Literals.unquote("'1'"));
        assertFalse(Literals.isQuoted("5"));
        assertEquals("5", Literals.unquote("5"));
    }

    @Test
    void figurativeConstantsAreResolvedToValues() {
        assertEquals("0", Literals.resolve("ZERO"));
        assertEquals("0", Literals.resolve("ZEROES"));
        assertEquals(" ", Literals.resolve("SPACES"));
        assertEquals("A", Literals.resolve("'A'"));
        assertTrue(Literals.isCodePageDependentFigurative("HIGH-VALUES"));
        assertFalse(Literals.isCodePageDependentFigurative("'HIGH-VALUES'"));
    }

    @Test
    void thruInsideQuotesIsNotARangeSeparator() {
        assertEquals(3, Literals.indexOfThru("'A' THRU 'Z'"));
        assertEquals(-1, Literals.indexOfThru("'A THRU B'"),
                "引用符の中の THRU は文字列 literal の一部であり区切りではない");
        assertEquals(-1, Literals.indexOfThru("'X'"));
    }

    @Test
    void mappingKindIsDerivedFromLineCounts() {
        assertEquals(MappingKind.ONE_TO_ONE,
                LineTrackingEmitter.kindOf(new LineRange(3, 3), new LineRange(7, 7)));
        assertEquals(MappingKind.ONE_TO_MANY,
                LineTrackingEmitter.kindOf(new LineRange(3, 3), new LineRange(7, 12)));
        assertEquals(MappingKind.MANY_TO_ONE,
                LineTrackingEmitter.kindOf(new LineRange(3, 8), new LineRange(7, 7)));
    }

    @Test
    void emitterTracksLineNumbersAndIndents() {
        LineTrackingEmitter out = new LineTrackingEmitter("    ");
        assertEquals(1, out.nextLine());
        out.emit("class Foo:");
        out.indent();
        out.emit("pass");
        assertEquals(2, out.lastLine());
        assertEquals("class Foo:\n    pass\n", out.render());
    }

    @Test
    void assembleSortsByStableKeyAndNumbersAnchorsInOrder() {
        PendingMapping later = new PendingMapping("SYK003", new LineRange(20, 20), "a.py",
                new LineRange(5, 6), MappingKind.ONE_TO_MANY, "");
        PendingMapping earlier = new PendingMapping("SYK003", new LineRange(10, 10), "a.py",
                new LineRange(1, 2), MappingKind.ONE_TO_MANY, "REDEFINES X");
        List<LineMappingEntry> entries =
                LineMapAssembler.assemble("SYK003", List.of(later, earlier));
        assertEquals(2, entries.size());
        assertEquals(10, entries.get(0).cobolLines().startLine());
        assertEquals("SYK003#0001", entries.get(0).anchorId());
        assertEquals("REDEFINES X", entries.get(0).note());
        assertEquals(20, entries.get(1).cobolLines().startLine());
        assertEquals("SYK003#0002", entries.get(1).anchorId());
    }
}
