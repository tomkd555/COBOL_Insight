package jp.cobolinsight.core.fix;

import jp.cobolinsight.core.encoding.CodePage;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedFormatNormalizerTest {

    private final FixedFormatNormalizer normalizer = new FixedFormatNormalizer();

    private static int byteLen(String line, Charset charset) {
        return line.getBytes(charset).length;
    }

    @Test
    void insertedStatementStartsAtBAreaColumn12() {
        List<String> lines = normalizer.layoutStatement(
                "IF SQLCODE NOT = 0 DISPLAY 'SQL ERROR: ' SQLCODE END-IF", StandardCharsets.UTF_8);

        assertEquals(1, lines.size());
        String line = lines.get(0);
        // The sequence number area (columns 1-6), the column-7 indicator area, and Area A (columns 8-11) are blank.
        assertEquals("           ", line.substring(0, 11));
        assertEquals(' ', line.charAt(6));
        // The content starts at the Area B origin (column 12 = index 11).
        assertEquals("IF SQLCODE NOT = 0 DISPLAY 'SQL ERROR: ' SQLCODE END-IF", line.substring(11));
        // Does not encroach into the identification area (columns 73-80).
        assertTrue(byteLen(line, StandardCharsets.UTF_8) <= 72);
    }

    @Test
    void contentFillingBAreaExactlyStaysOnOneLine() {
        // Area B is columns 12-72, 61 columns wide. Exactly 61 bytes of ASCII does not wrap.
        String content = "A".repeat(61);
        List<String> lines = normalizer.layoutStatement(content, StandardCharsets.UTF_8);

        assertEquals(1, lines.size());
        assertEquals(72, byteLen(lines.get(0), StandardCharsets.UTF_8));
    }

    @Test
    void reservedTrailingBytesShrinkTheBudgetOfTheFinalLineOnly() {
        // Assumes the caller appends 1 byte (the terminating period) after formatting. Only the
        // final physical line's budget shrinks to 60 bytes; lines before it still use the full 61 bytes.
        String statement = "A".repeat(55) + " " + "B".repeat(5);
        assertEquals(61, byteLen(statement, StandardCharsets.UTF_8));

        List<String> withoutReserve =
                normalizer.layoutStatement(statement, StandardCharsets.UTF_8);
        assertEquals(1, withoutReserve.size());
        assertEquals(72, byteLen(withoutReserve.get(0), StandardCharsets.UTF_8));

        List<String> reserved = normalizer.layoutStatement(statement, StandardCharsets.UTF_8, 1);
        assertEquals(2, reserved.size());
        assertEquals("           " + "A".repeat(55), reserved.get(0));
        assertEquals("           " + "B".repeat(5), reserved.get(1));
        // Does not exceed column 72 even after appending a period at the end.
        assertTrue(byteLen(reserved.get(1) + ".", StandardCharsets.UTF_8) <= 72);
    }

    @Test
    void reservedTrailingBytesDoNotShrinkLinesBeforeTheFinalOne() {
        String statement = "A".repeat(61) + " " + "B".repeat(5);

        List<String> lines = normalizer.layoutStatement(statement, StandardCharsets.UTF_8, 1);

        assertEquals(2, lines.size());
        // Line 1, which is not the final line, uses the full 61 bytes of Area B.
        assertEquals(72, byteLen(lines.get(0), StandardCharsets.UTF_8));
        assertEquals("           " + "B".repeat(5), lines.get(1));
    }

    @Test
    void asciiOverflowWrapsToContinuationLine() {
        // 62 bytes does not fit in Area B's 61 columns, so it wraps to a continuation line (a '-' at column 7).
        String content = "A".repeat(62);
        List<String> lines = normalizer.layoutStatement(content, StandardCharsets.UTF_8);

        assertEquals(2, lines.size());
        assertEquals("           " + "A".repeat(61), lines.get(0));
        assertEquals('-', lines.get(1).charAt(6));
        assertEquals("      -    " + "A", lines.get(1));
        for (String line : lines) {
            assertTrue(byteLen(line, StandardCharsets.UTF_8) <= 72);
        }
    }

    @Test
    void fullWidthOverflowWrapsAtCharBoundaryWithoutSplittingBytes() {
        // A Shift_JIS full-width character is 2 bytes. Area B's 61 bytes fit up to 30 characters (60 bytes); the 31st character wraps.
        Charset sjis = CodePage.SHIFT_JIS.charset();
        String content = "あ".repeat(31);
        List<String> lines = normalizer.layoutStatement(content, sjis);

        assertEquals(2, lines.size());
        // Line 1's Area B holds exactly 30 characters (does not split a full-width character).
        assertEquals("あ".repeat(30), lines.get(0).substring(11));
        assertEquals("あ", lines.get(1).substring(11));
        for (String line : lines) {
            assertTrue(byteLen(line, sjis) <= 72, "各行は72バイト以下: " + line);
            // Re-decoding produces no garbled characters, meaning the wrap is at a character boundary.
            assertEquals(line, new String(line.getBytes(sjis), sjis));
        }
    }

    @Test
    void multiLineWrapCoversAllContentInOrder() {
        String content = "B".repeat(130);
        List<String> lines = normalizer.layoutStatement(content, StandardCharsets.UTF_8);

        assertEquals(3, lines.size());
        String rejoined = lines.stream()
                .map(line -> line.substring(11))
                .reduce("", String::concat);
        assertEquals(content, rejoined);
        assertEquals(' ', lines.get(0).charAt(6));
        assertEquals('-', lines.get(1).charAt(6));
        assertEquals('-', lines.get(2).charAt(6));
    }

    @Test
    void multiWordStatementFoldsAtWordBoundaryWithoutHyphen() {
        // A check statement that R017's (unchecked file status) fix suggestion inserts. At 81 bytes
        // it exceeds Area B's budget (61 bytes) and wraps at a word boundary.
        String handler =
                "IF WS-ORDIN-STATUS NOT = '00' DISPLAY 'FILE ERROR: ORDIN ' WS-ORDIN-STATUS END-IF";
        assertEquals(81, byteLen(handler, StandardCharsets.UTF_8));

        List<String> lines = normalizer.layoutStatement(handler, StandardCharsets.UTF_8);

        assertEquals(2, lines.size());
        for (String line : lines) {
            // A word-boundary wrap does not place a continuation indicator '-'; column 7 stays blank.
            assertEquals(' ', line.charAt(6), "語境界の折り返しに '-' を置かない: " + line);
            // The sequence number area (columns 1-6) and the column-7 indicator area are blank; A area (columns 8-11) is blank; content starts at column 12.
            assertEquals("           ", line.substring(0, 11));
            assertTrue(byteLen(line, StandardCharsets.UTF_8) <= 72, "各行は72バイト以下: " + line);
        }
        // Rejoining with newlines restored as spaces reproduces the original text (tokens are not merged).
        String rejoined = lines.stream()
                .map(line -> line.substring(11))
                .reduce((a, b) -> a + " " + b)
                .orElseThrow();
        assertEquals(handler, rejoined);
        // A literal containing internal spaces is kept intact on one line rather than being split.
        assertTrue(lines.get(0).contains("'FILE ERROR: ORDIN '"),
                "空白を含むリテラルは分断しない: " + lines.get(0));
    }

    @Test
    void plainWordsFoldAtSpaceKeepTokensSeparate() {
        // A statement of consecutive words that exceeds Area B's budget. Verifies it wraps at word boundaries and adjacent words do not merge.
        String statement =
                "MOVE AAAAAAAAAA TO BBBBBBBBBB MOVE CCCCCCCCCC TO DDDDDDDDDD MOVE EEEE TO FFFF";
        assertTrue(byteLen(statement, StandardCharsets.UTF_8) > 61);

        List<String> lines = normalizer.layoutStatement(statement, StandardCharsets.UTF_8);

        assertTrue(lines.size() >= 2, "予算超過で複数行へ折る");
        for (String line : lines) {
            assertEquals(' ', line.charAt(6), "語境界の折り返しに '-' を置かない: " + line);
            assertTrue(byteLen(line, StandardCharsets.UTF_8) <= 72);
        }
        String rejoined = lines.stream()
                .map(line -> line.substring(11))
                .reduce((a, b) -> a + " " + b)
                .orElseThrow();
        assertEquals(statement, rejoined);
    }

    @Test
    void oversizedLiteralSplitsWithReinsertedOpeningQuote() {
        // A single-token literal is split mid-way across continuation lines only when it exceeds Area B's budget.
        String literal = "'" + "X".repeat(130) + "'";
        List<String> lines = normalizer.layoutStatement(literal, StandardCharsets.UTF_8);

        assertEquals(3, lines.size());
        // The first line starts at a word boundary, so it has no '-'; the continuation lines that follow do.
        assertEquals(' ', lines.get(0).charAt(6));
        assertEquals('-', lines.get(1).charAt(6));
        assertEquals('-', lines.get(2).charAt(6));
        // A mid-split line is padded out to exactly column 72 (a shorter line would let trailing whitespace leak into the literal).
        assertEquals(72, byteLen(lines.get(0), StandardCharsets.UTF_8));
        assertEquals(72, byteLen(lines.get(1), StandardCharsets.UTF_8));
        assertTrue(byteLen(lines.get(2), StandardCharsets.UTF_8) <= 72);
        // A continuation line's content begins with the reinserted opening quote.
        assertEquals('\'', lines.get(1).charAt(11));
        assertEquals('\'', lines.get(2).charAt(11));

        // Restoring by COBOL continuation rules reproduces the original literal (reinserting the quote does not corrupt it).
        StringBuilder body = new StringBuilder();
        for (int k = 0; k < lines.size(); k++) {
            String content = lines.get(k).substring(11).substring(1); // strip the leading quote
            if (k == lines.size() - 1) {
                content = content.substring(0, content.length() - 1); // strip the trailing closing quote
            }
            body.append(content);
        }
        assertEquals(literal, "'" + body + "'");
    }
}
