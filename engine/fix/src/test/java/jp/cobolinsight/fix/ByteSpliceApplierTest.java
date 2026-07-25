package jp.cobolinsight.fix;

import jp.cobolinsight.encoding.CodePage;
import jp.cobolinsight.encoding.DecodedSource;
import jp.cobolinsight.encoding.SourceDecoder;
import jp.cobolinsight.engineapi.finding.TextEdit;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.source.SourceRange;
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
        // 2行目先頭(column=1)へ "X" を挿入。
        byte[] result = applier.apply(decoded, List.of(edit(2, 1, 2, 1, "X")));
        assertArrayEquals("ABCDE\nXFGHIJ".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    void replacementCoversEndExclusiveRange() {
        DecodedSource decoded = decode("ABCDE\nFGHIJ", CodePage.UTF_8);
        // column は1始まり。start=2 は B、end=5 は排他で B,C,D を置換する。
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
        // 入力順が範囲昇順でなくても結果は同一(内部で昇順ソート・後方適用)。
        byte[] result = applier.apply(decoded, List.of(
                edit(2, 1, 2, 1, "Z"),   // 2行目先頭へ挿入
                edit(1, 1, 1, 2, "a")));  // 1行目 A を a へ
        assertArrayEquals("aBCDE\nZFGHIJ".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    void shiftJisByteOffsetsRespectFullWidthWidth() {
        // A(1) B(1) 日(2) C(1) = 5バイト。日 の後(C の前、column=4)へ ASCII "Z" を挿入。
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
            // 自動判別が一意に効くよう ASCII で検証する(パス読込→再復号→スプライスの配線確認)。
            Files.write(tmp, "ABCDE\nFGHIJ".getBytes(StandardCharsets.UTF_8));
            byte[] result = applier.apply(tmp, List.of(edit(2, 1, 2, 1, "X")));
            assertArrayEquals("ABCDE\nXFGHIJ".getBytes(StandardCharsets.UTF_8), result);
        } finally {
            Files.deleteIfExists(tmp);
        }
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
