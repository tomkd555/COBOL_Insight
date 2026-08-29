package jp.cobolinsight.core.encoding;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ByteOffsetTableTest {

    private final SourceDecoder decoder = new SourceDecoder();

    @Test
    void asciiOnlyOffsetsAreIdentityMapping() {
        byte[] bytes = "ABC\r\nDEF".getBytes(StandardCharsets.UTF_8);
        ByteOffsetTable table = decoder.decode(bytes, CodePage.UTF_8).offsetTable();

        for (int i = 0; i < 8; i++) {
            assertEquals(i, table.byteOffsetOfChar(i));
        }
        assertEquals(8, table.byteOffsetOfChar(8));
    }

    @Test
    void utf8JapaneseMixedOffsets() {
        byte[] bytes = "AB日本C".getBytes(StandardCharsets.UTF_8);
        ByteOffsetTable table = decoder.decode(bytes, CodePage.UTF_8).offsetTable();

        assertEquals(0, table.byteOffsetOfChar(0)); // A
        assertEquals(1, table.byteOffsetOfChar(1)); // B
        assertEquals(2, table.byteOffsetOfChar(2)); // 日 (3バイト)
        assertEquals(5, table.byteOffsetOfChar(3)); // 本 (3バイト)
        assertEquals(8, table.byteOffsetOfChar(4)); // C
        assertEquals(9, table.byteOffsetOfChar(5));
    }

    @Test
    void shiftJisJapaneseMixedOffsets() {
        byte[] bytes = "AB日本C".getBytes(CodePage.SHIFT_JIS.charset());
        ByteOffsetTable table = decoder.decode(bytes, CodePage.SHIFT_JIS).offsetTable();

        assertEquals(2, table.byteOffsetOfChar(2)); // 日 (2バイト)
        assertEquals(4, table.byteOffsetOfChar(3)); // 本 (2バイト)
        assertEquals(6, table.byteOffsetOfChar(4)); // C
        assertEquals(7, table.byteOffsetOfChar(5));
    }

    @Test
    void ebcdicSoSiMixedOffsetsSkipShiftBytes() {
        // x-IBM939: A B SO 日(2) 本(2) SI C の9バイト
        byte[] bytes = "AB日本C".getBytes(CodePage.IBM939.charset());
        assertEquals(9, bytes.length);

        ByteOffsetTable table = decoder.decode(bytes, CodePage.IBM939).offsetTable();

        assertEquals(0, table.byteOffsetOfChar(0)); // A
        assertEquals(1, table.byteOffsetOfChar(1)); // B
        assertEquals(3, table.byteOffsetOfChar(2)); // 日 (SO の次)
        assertEquals(5, table.byteOffsetOfChar(3)); // 本
        assertEquals(8, table.byteOffsetOfChar(4)); // C (SI の次)
        assertEquals(9, table.byteOffsetOfChar(5));
    }

    @Test
    void lineStartOffsetsAndInLineColumnLookup() {
        byte[] bytes = "AAA\r\n日本\r\nBB".getBytes(StandardCharsets.UTF_8);
        ByteOffsetTable table = decoder.decode(bytes, CodePage.UTF_8).offsetTable();

        assertEquals(3, table.lineCount());
        assertEquals(0, table.lineStartByteOffset(1));
        assertEquals(5, table.lineStartByteOffset(2));
        assertEquals(13, table.lineStartByteOffset(3));
        assertEquals(8, table.byteOffsetAt(2, 1));  // 2行目の「本」
        assertEquals(14, table.byteOffsetAt(3, 1)); // 3行目の2文字目のB
    }
}
