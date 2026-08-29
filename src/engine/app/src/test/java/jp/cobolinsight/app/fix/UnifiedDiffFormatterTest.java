package jp.cobolinsight.app.fix;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies unified diff computation ({@link UnifiedDiffFormatter}). */
class UnifiedDiffFormatterTest {

    private final UnifiedDiffFormatter formatter = new UnifiedDiffFormatter();

    @Test
    void emitsHunkAndHeadersForInsertedLine() {
        String original = String.join("\n", "AAA", "BBB", "CCC", "");
        String fixed = String.join("\n", "AAA", "BBB", "INSERTED", "CCC", "");

        List<String> diff = formatter.unifiedDiff("cobol/X.cbl", original, fixed);

        assertTrue(diff.stream().anyMatch(l -> l.equals("--- a/cobol/X.cbl")), diff.toString());
        assertTrue(diff.stream().anyMatch(l -> l.equals("+++ b/cobol/X.cbl")), diff.toString());
        assertTrue(diff.stream().anyMatch(l -> l.startsWith("@@")), diff.toString());
        assertTrue(diff.stream().anyMatch(l -> l.equals("+INSERTED")), diff.toString());
        assertTrue(diff.stream().noneMatch(l -> l.equals("-BBB")),
                "変更のない行は削除として出ないこと: " + diff);
    }

    @Test
    void emitsEmptyDiffWhenTextIsUnchanged() {
        String text = String.join("\n", "AAA", "BBB", "");
        assertEquals(List.of(), formatter.unifiedDiff("cobol/X.cbl", text, text));
    }
}
