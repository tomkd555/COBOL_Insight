package jp.cobolinsight.core.fix;

import jp.cobolinsight.core.encoding.ByteOffsetTable;
import jp.cobolinsight.core.encoding.DecodedSource;
import jp.cobolinsight.core.encoding.SourceDecoder;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.source.SourcePosition;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A byte-splice applier that locally applies minimal edits to the original byte sequence.
 *
 * <p>Each {@link TextEdit}'s {@link jp.cobolinsight.core.source.SourceRange} is based on
 * (line, column), with both line and column starting at 1 (the {@link SourcePosition}
 * convention). Columns are converted to the 0-based values expected by
 * {@link ByteOffsetTable#byteOffsetAt(int, int)} via {@code column-1} before looking up the byte
 * offset. The end of a range is exclusive, and an empty range where start and end are equal
 * represents an insertion point.
 *
 * <p>Edits are assumed to be in ascending, non-overlapping order and are applied from the end
 * backward to avoid position drift. When there are no edits, the original byte sequence is
 * returned unchanged. The replacement text is byte-encoded in the same encoding as the original,
 * so the output ends up in the same encoding as the original.
 */
public final class ByteSpliceApplier {

    private final SourceDecoder decoder = new SourceDecoder();

    /** Re-decodes the original file with automatic detection and returns the fixed byte sequence with the edits applied. */
    public byte[] apply(Path originalFile, List<TextEdit> edits) throws IOException {
        byte[] original = Files.readAllBytes(originalFile);
        return apply(decoder.decode(original), edits);
    }

    /** Returns the fixed byte sequence obtained by applying the edits to the decoded source. */
    public byte[] apply(DecodedSource decoded, List<TextEdit> edits) {
        if (edits.isEmpty()) {
            return decoded.originalBytes();
        }
        Charset charset = decoded.encodingInfo().codePage().charset();
        ByteOffsetTable table = decoded.offsetTable();

        byte[] originalBytes = decoded.originalBytes();
        String separator = lineSeparatorOf(originalBytes);
        boolean endsWithBreak = originalBytes.length == 0
                || originalBytes[originalBytes.length - 1] == '\n';

        List<ByteEdit> byteEdits = new ArrayList<>(edits.size());
        for (TextEdit edit : edits) {
            int start = byteOffsetOf(table, edit.range().start());
            int end = byteOffsetOf(table, edit.range().end());
            String replacement = withSeparator(edit.replacement(), separator);
            // When inserting right after the last line, start with a line break if the original does not end with one.
            if (start == originalBytes.length && !endsWithBreak) {
                replacement = separator + replacement;
            }
            byteEdits.add(new ByteEdit(start, end, replacement.getBytes(charset)));
        }
        byteEdits.sort(Comparator.comparingInt(ByteEdit::start));
        rejectOverlap(byteEdits);

        byte[] result = originalBytes;
        for (int i = byteEdits.size() - 1; i >= 0; i--) {
            result = splice(result, byteEdits.get(i));
        }
        return result;
    }

    private static int byteOffsetOf(ByteOffsetTable table, SourcePosition position) {
        return table.byteOffsetAt(position.line(), position.column() - 1);
    }

    /** The original's line-break style. An original consisting solely of CRLF is treated as CRLF; otherwise LF. */
    private static String lineSeparatorOf(byte[] original) {
        int breaks = 0;
        int crlf = 0;
        for (int i = 0; i < original.length; i++) {
            if (original[i] == '\n') {
                breaks++;
                if (i > 0 && original[i - 1] == '\r') {
                    crlf++;
                }
            }
        }
        return breaks > 0 && breaks == crlf ? "\r\n" : "\n";
    }

    /** Aligns line breaks in the replacement text to the original's line-break style, so as not to produce mixed line breaks. */
    private static String withSeparator(String replacement, String separator) {
        String normalized = replacement.replace("\r\n", "\n");
        return separator.equals("\n") ? normalized : normalized.replace("\n", separator);
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
