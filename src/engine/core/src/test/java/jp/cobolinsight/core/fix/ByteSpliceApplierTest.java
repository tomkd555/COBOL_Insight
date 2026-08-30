package jp.cobolinsight.core.fix;

import jp.cobolinsight.core.encoding.CodePage;
import jp.cobolinsight.core.encoding.DecodedSource;
import jp.cobolinsight.core.encoding.SourceDecoder;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ByteSpliceApplierTest {

    private static final String FILE = "T.cbl";

    private final SourceDecoder decoder = new SourceDecoder();
    private final ByteSpliceApplier applier = new ByteSpliceApplier();

    private DecodedSource decode(String text, CodePage codePage) {
        return decoder.decode(text.getBytes(codePage.charset()), codePage);
    }

    private static SourcePosition pos(int line, int column) {
        return new SourcePosition(FILE, line, column, SourcePosition.UNKNOWN_BYTE_OFFSET);
    }

    private static TextEdit edit(int startLine, int startCol, int endLine, int endCol, String replacement) {
        return new TextEdit(new SourceRange(pos(startLine, startCol), pos(endLine, endCol)), replacement);
    }

    @Test
    void emptyEditsReturnOriginalBytesIdentity() {
        DecodedSource decoded = decode("ABCDE\nFGHIJ", CodePage.UTF_8);
        byte[] result = applier.apply(decoded, List.of());
        assertArrayEquals(decoded.originalBytes(), result);
    }

    @Test
    void insertionAtLineStartUsesZeroWidthRange() {
        DecodedSource decoded = decode("ABCDE\nFGHIJ", CodePage.UTF_8);
        // Inserts "X" at the start of line 2 (column=1).
        byte[] result = applier.apply(decoded, List.of(edit(2, 1, 2, 1, "X")));
        assertArrayEquals("ABCDE\nXFGHIJ".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    void replacementCoversEndExclusiveRange() {
        DecodedSource decoded = decode("ABCDE\nFGHIJ", CodePage.UTF_8);
        // column is 1-based. start=2 is B; end=5 is exclusive, so B, C, D are replaced.
        byte[] result = applier.apply(decoded, List.of(edit(1, 2, 1, 5, "xyz")));
        assertArrayEquals("AxyzE\nFGHIJ".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    void emptyReplacementDeletesRange() {
        DecodedSource decoded = decode("ABCDE\nFGHIJ", CodePage.UTF_8);
        byte[] result = applier.apply(decoded, List.of(edit(1, 2, 1, 5, "")));
        assertArrayEquals("AE\nFGHIJ".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    void multipleNonOverlappingEditsAppliedIndependentOfOrder() {
        DecodedSource decoded = decode("ABCDE\nFGHIJ", CodePage.UTF_8);
        // The result is the same regardless of input order (internally sorted ascending, applied from the back).
        byte[] result = applier.apply(decoded, List.of(
                edit(2, 1, 2, 1, "Z"),   // insert at the start of line 2
                edit(1, 1, 1, 2, "a")));  // change A on line 1 to a
        assertArrayEquals("aBCDE\nZFGHIJ".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    void shiftJisByteOffsetsRespectFullWidthWidth() {
        // A(1) B(1) 日(2) C(1) = 5 bytes. Inserts ASCII "Z" after 日 (before C, column=4).
        DecodedSource decoded = decode("AB日C", CodePage.SHIFT_JIS);
        byte[] original = decoded.originalBytes();
        byte[] result = applier.apply(decoded, List.of(edit(1, 4, 1, 4, "Z")));

        Charset sjis = CodePage.SHIFT_JIS.charset();
        byte[] expected = concat(
                slice(original, 0, 4),          // A B 日
                "Z".getBytes(sjis),
                slice(original, 4, original.length)); // C
        assertArrayEquals(expected, result);
    }

    @Test
    void overlappingEditsRejected() {
        DecodedSource decoded = decode("ABCDE", CodePage.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> applier.apply(decoded, List.of(
                edit(1, 1, 1, 3, "x"),
                edit(1, 2, 1, 4, "y"))));
    }

    @Test
    void applyReadsAndReDecodesOriginalFile() throws IOException {
        Path tmp = Files.createTempFile("splice", ".cbl");
        try {
            // Verifies with ASCII so auto-detection resolves unambiguously (checks the path-read -> redecode -> splice wiring).
            Files.write(tmp, "ABCDE\nFGHIJ".getBytes(StandardCharsets.UTF_8));
            byte[] result = applier.apply(tmp, List.of(edit(2, 1, 2, 1, "X")));
            assertArrayEquals("ABCDE\nXFGHIJ".getBytes(StandardCharsets.UTF_8), result);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void insertionAfterLastLineOfFileWithoutTrailingBreakAddsBreak() {
        DecodedSource decoded = decode("ABCDE\nFGHIJ", CodePage.UTF_8);
        // The position right after the last line (line 2) is represented as the start of line 3. Since the original does not end with a newline, the insertion starts with one.
        byte[] result = applier.apply(decoded, List.of(edit(3, 1, 3, 1, "KLMNO\n")));
        assertArrayEquals("ABCDE\nFGHIJ\nKLMNO\n".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    void insertionAfterLastLineOfFileWithTrailingBreakKeepsSingleBreak() {
        DecodedSource decoded = decode("ABCDE\nFGHIJ\n", CodePage.UTF_8);
        byte[] result = applier.apply(decoded, List.of(edit(3, 1, 3, 1, "KLMNO\n")));
        assertArrayEquals("ABCDE\nFGHIJ\nKLMNO\n".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    void insertionFollowsCrlfLineSeparatorOfOriginal() {
        DecodedSource decoded = decode("ABCDE\r\nFGHIJ\r\n", CodePage.UTF_8);
        byte[] result = applier.apply(decoded, List.of(edit(2, 1, 2, 1, "KLMNO\n")));
        assertArrayEquals("ABCDE\r\nKLMNO\r\nFGHIJ\r\n".getBytes(StandardCharsets.UTF_8), result);
    }

    private static byte[] slice(byte[] src, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(src, from, out, 0, to - from);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
