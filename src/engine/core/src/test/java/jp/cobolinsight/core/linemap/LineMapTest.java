package jp.cobolinsight.core.linemap;

import jp.cobolinsight.core.source.LineRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LineMapTest {

    @Test
    void entryHoldsAllSevenFields() {
        LineMappingEntry entry = new LineMappingEntry("A.cbl", new LineRange(10, 12),
                "a_translated.py", new LineRange(20, 20), MappingKind.MANY_TO_ONE,
                "GO TO は構造化のため N:1 対応で表現する", "anchor-0001");
        assertEquals("A.cbl", entry.cobolSourceId());
        assertEquals(10, entry.cobolLines().startLine());
        assertEquals("a_translated.py", entry.generatedFile());
        assertEquals(MappingKind.MANY_TO_ONE, entry.mappingKind());
        assertEquals("anchor-0001", entry.anchorId());
    }

    @Test
    void entryAllowsEmptyNoteForDirectTranslation() {
        LineMappingEntry entry = new LineMappingEntry("A.cbl", new LineRange(1, 1),
                "a.py", new LineRange(1, 1), MappingKind.ONE_TO_ONE, "", "anchor-0002");
        assertEquals("", entry.note());
    }

    @Test
    void entryRejectsBlankAnchorId() {
        assertThrows(IllegalArgumentException.class, () -> new LineMappingEntry("A.cbl",
                new LineRange(1, 1), "a.py", new LineRange(1, 1), MappingKind.ONE_TO_ONE, "", " "));
    }

    @Test
    void mappingKindsCoverThreeKinds() {
        assertEquals(3, MappingKind.values().length);
    }
}
