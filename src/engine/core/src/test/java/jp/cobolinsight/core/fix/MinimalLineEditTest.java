package jp.cobolinsight.core.fix;

import jp.cobolinsight.core.encoding.CodePage;
import jp.cobolinsight.core.encoding.DecodedSource;
import jp.cobolinsight.core.encoding.SourceDecoder;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that writing back the full text edited in the screen splices only the differing lines,
 * preserving the original's code page, line-ending style, and the byte content of untouched lines.
 */
class MinimalLineEditTest {

    private static final String FILE = "T.cbl";

    private final SourceDecoder decoder = new SourceDecoder();

    private DecodedSource decode(String text, CodePage codePage) {
        return decoder.decode(text.getBytes(codePage.charset()), codePage);
    }

    @Test
    void identicalTextIsNoOpAndKeepsOriginalBytes() {
        DecodedSource original = decode("AAA\n日本語\nCCC\n", CodePage.SHIFT_JIS);

        MinimalLineEdit.Result result = MinimalLineEdit.apply(FILE, original, original.text());

        assertFalse(result.changed(), "同一内容は編集を作らないこと");
        assertArrayEquals(original.originalBytes(), result.bytes());
        assertEquals(0, result.changedLineFrom());
        assertEquals(0, result.changedLineTo());
    }

    @Test
    void middleLineEditKeepsSurroundingBytesIdenticalInShiftJis() {
        String text = "AAA\n日本語テスト\nCCC\n";
        DecodedSource original = decode(text, CodePage.SHIFT_JIS);
        String edited = "AAA\n日本語カナ\nCCC\n";

        MinimalLineEdit.Result result = MinimalLineEdit.apply(FILE, original, edited);

        assertTrue(result.changed());
        assertEquals(2, result.changedLineFrom());
        assertEquals(2, result.changedLineTo());
        // Only line 2 is replaced; the byte content of the lines before and after must not differ from the original by even one byte.
        int head = original.offsetTable().lineStartByteOffset(2);
        assertArrayEquals(Arrays.copyOf(original.originalBytes(), head),
                Arrays.copyOf(result.bytes(), head));
        byte[] tail = "CCC\n".getBytes(CodePage.SHIFT_JIS.charset());
        assertArrayEquals(tail,
                Arrays.copyOfRange(result.bytes(), result.bytes().length - tail.length,
                        result.bytes().length));
        assertArrayEquals(edited.getBytes(CodePage.SHIFT_JIS.charset()), result.bytes());
    }

    @Test
    void ebcdicEditRoundTripsThroughSoSiFraming() {
        String text = "AAA\n日本語テスト\nCCC\n";
        DecodedSource original = decode(text, CodePage.IBM930);
        String edited = "AAA\n日本語カナ\nCCC\n";

        MinimalLineEdit.Result result = MinimalLineEdit.apply(FILE, original, edited);

        // SO/SI are closed within the replaced line, so the result must match encoding the full text.
        assertArrayEquals(edited.getBytes(CodePage.IBM930.charset()), result.bytes());
        int head = original.offsetTable().lineStartByteOffset(2);
        assertArrayEquals(Arrays.copyOf(original.originalBytes(), head),
                Arrays.copyOf(result.bytes(), head));
    }

    @Test
    void unmappableCharacterIsRejectedBeforeProducingBytes() {
        DecodedSource original = decode("AAA\nBBB\n", CodePage.SHIFT_JIS);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MinimalLineEdit.apply(FILE, original, "AAA\nB🙂B\n"));

        assertTrue(e.getMessage().contains("windows-31j"), e.getMessage());
        assertTrue(e.getMessage().contains("符号化できない"), e.getMessage());
    }

    @Test
    void ebcdicRejectsUnmappableCharacterToo() {
        DecodedSource original = decode("AAA\nBBB\n", CodePage.IBM930);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MinimalLineEdit.apply(FILE, original, "AAA\nB🙂B\n"));

        assertTrue(e.getMessage().contains("x-IBM930"), e.getMessage());
    }

    @Test
    void ebcdicSourceStartingWithShiftCodeRefusesFirstLineEdit() {
        // When line 1 starts with DBCS, SO is placed before the line's first character, so it cannot be brought into the replacement range.
        DecodedSource original = decode("日本語\nBBB\n", CodePage.IBM930);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MinimalLineEdit.apply(FILE, original, "日本\nBBB\n"));

        assertTrue(e.getMessage().contains("シフトコード"), e.getMessage());
    }

    @Test
    void ebcdicSourceStartingWithShiftCodeStillAllowsLaterLineEdit() {
        DecodedSource original = decode("日本語\nBBB\n", CodePage.IBM930);

        MinimalLineEdit.Result result = MinimalLineEdit.apply(FILE, original, "日本語\nカナ\n");

        assertArrayEquals("日本語\nカナ\n".getBytes(CodePage.IBM930.charset()), result.bytes());
    }

    @Test
    void crlfIsPreservedWhenEditedTextUsesLineFeedOnly() {
        DecodedSource original = decode("AAA\r\nBBB\r\nCCC\r\n", CodePage.UTF_8);

        MinimalLineEdit.Result result = MinimalLineEdit.apply(FILE, original, "AAA\nXXX\nCCC\n");

        assertArrayEquals("AAA\r\nXXX\r\nCCC\r\n".getBytes(CodePage.UTF_8.charset()),
                result.bytes());
    }

    @Test
    void missingTrailingNewlineIsPreserved() {
        DecodedSource original = decode("AAA\r\nBBB", CodePage.UTF_8);

        MinimalLineEdit.Result result = MinimalLineEdit.apply(FILE, original, "AAA\nXXX");

        assertArrayEquals("AAA\r\nXXX".getBytes(CodePage.UTF_8.charset()), result.bytes());
    }

    @Test
    void trailingNewlineRemovalIsExpressedAsLastLineEdit() {
        DecodedSource original = decode("AAA\nBBB\n", CodePage.UTF_8);

        MinimalLineEdit.Result result = MinimalLineEdit.apply(FILE, original, "AAA\nBBB");

        assertArrayEquals("AAA\nBBB".getBytes(CodePage.UTF_8.charset()), result.bytes());
        assertEquals(2, result.changedLineFrom());
        assertEquals(2, result.changedLineTo());
    }

    @Test
    void appendedLineIsInsertedAtEndOfText() {
        DecodedSource original = decode("AAA\nBBB\n", CodePage.UTF_8);

        MinimalLineEdit.Result result = MinimalLineEdit.apply(FILE, original, "AAA\nBBB\nCCC\n");

        assertArrayEquals("AAA\nBBB\nCCC\n".getBytes(CodePage.UTF_8.charset()), result.bytes());
        // When only a line insertion occurs, the end line is one before the start line (the line right before the insertion point).
        assertEquals(3, result.changedLineFrom());
        assertEquals(2, result.changedLineTo());
    }

    @Test
    void deletedLineRemovesItsLineBreakToo() {
        DecodedSource original = decode("AAA\nBBB\nCCC\n", CodePage.UTF_8);

        MinimalLineEdit.Result result = MinimalLineEdit.apply(FILE, original, "AAA\nCCC\n");

        assertArrayEquals("AAA\nCCC\n".getBytes(CodePage.UTF_8.charset()), result.bytes());
        assertEquals(2, result.changedLineFrom());
        assertEquals(2, result.changedLineTo());
    }

    @Test
    void byteOrderMarkIsCarriedOverUntouched() {
        byte[] bytes = "﻿AAA\nBBB\n".getBytes(CodePage.UTF_8.charset());
        DecodedSource original = decoder.decode(bytes, CodePage.UTF_8);

        MinimalLineEdit.Result result = MinimalLineEdit.apply(FILE, original, "AAA\nXXX\n");

        assertArrayEquals("﻿AAA\nXXX\n".getBytes(CodePage.UTF_8.charset()), result.bytes());
    }
}
