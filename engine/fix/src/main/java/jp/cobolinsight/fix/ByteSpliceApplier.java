package jp.cobolinsight.fix;

import jp.cobolinsight.encoding.ByteOffsetTable;
import jp.cobolinsight.encoding.DecodedSource;
import jp.cobolinsight.encoding.SourceDecoder;
import jp.cobolinsight.engineapi.finding.TextEdit;
import jp.cobolinsight.engineapi.source.SourcePosition;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 原バイト列へ最小編集を局所適用するバイトスプライス適用器。
 *
 * <p>各 {@link TextEdit} の {@link jp.cobolinsight.engineapi.source.SourceRange} は
 * (行・桁)基準で、行・桁ともに1始まりである({@link SourcePosition} の規約)。桁は
 * {@link ByteOffsetTable#byteOffsetAt(int, int)} が受ける0始まりへ {@code column-1} で
 * 変換してバイトオフセットを引く。範囲の終端は排他で、start と end が同一の空範囲は挿入点を表す。
 *
 * <p>編集は範囲昇順・非重複を前提とし、位置ずれを避けるため後方から適用する。無編集は原バイト列を
 * 恒等で返す。置換テキストは原本と同一のエンコーディングでバイト化するため、出力は原本と同一の
 * エンコーディングになる。
 */
public final class ByteSpliceApplier {

    private final SourceDecoder decoder = new SourceDecoder();

    /** 原本ファイルを自動判別で再復号し、編集群を適用した修正後バイト列を返す。 */
    public byte[] apply(Path originalFile, List<TextEdit> edits) throws IOException {
        byte[] original = Files.readAllBytes(originalFile);
        return apply(decoder.decode(original), edits);
    }

    /** 復号済みソースへ編集群を適用した修正後バイト列を返す。 */
    public byte[] apply(DecodedSource decoded, List<TextEdit> edits) {
        if (edits.isEmpty()) {
            return decoded.originalBytes();
        }
        Charset charset = decoded.encodingInfo().codePage().charset();
        ByteOffsetTable table = decoded.offsetTable();

        List<ByteEdit> byteEdits = new ArrayList<>(edits.size());
        for (TextEdit edit : edits) {
            int start = byteOffsetOf(table, edit.range().start());
            int end = byteOffsetOf(table, edit.range().end());
            byteEdits.add(new ByteEdit(start, end, edit.replacement().getBytes(charset)));
        }
        byteEdits.sort(Comparator.comparingInt(ByteEdit::start));
        rejectOverlap(byteEdits);

        byte[] result = decoded.originalBytes();
        for (int i = byteEdits.size() - 1; i >= 0; i--) {
            result = splice(result, byteEdits.get(i));
        }
        return result;
    }

    private static int byteOffsetOf(ByteOffsetTable table, SourcePosition position) {
        return table.byteOffsetAt(position.line(), position.column() - 1);
    }

    private static void rejectOverlap(List<ByteEdit> edits) {
        for (int i = 1; i < edits.size(); i++) {
            if (edits.get(i).start() < edits.get(i - 1).end()) {
                throw new IllegalArgumentException(
                        "編集範囲が重複している: " + edits.get(i - 1) + " と " + edits.get(i));
            }
        }
    }

    private static byte[] splice(byte[] source, ByteEdit edit) {
        byte[] replacement = edit.replacement();
        byte[] result = new byte[source.length - (edit.end() - edit.start()) + replacement.length];
        System.arraycopy(source, 0, result, 0, edit.start());
        System.arraycopy(replacement, 0, result, edit.start(), replacement.length);
        System.arraycopy(source, edit.end(), result, edit.start() + replacement.length,
                source.length - edit.end());
        return result;
    }

    private record ByteEdit(int start, int end, byte[] replacement) {
    }
}
