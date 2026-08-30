package jp.cobolinsight.core.fix;

import jp.cobolinsight.core.encoding.ByteOffsetTable;
import jp.cobolinsight.core.encoding.CodePage;
import jp.cobolinsight.core.encoding.DecodedSource;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;

/**
 * Compares the full edited text against the original's decoded text, collapses just the
 * differing middle section into a single {@link TextEdit}, and byte-splices it with
 * {@link ByteSpliceApplier}.
 *
 * <p>Line by line, matching sections are trimmed from the start and end, and the remaining
 * middle line range is replaced wholesale. No diff algorithm (LCS) is used. Since the original
 * byte sequence is carried through unchanged outside the edit, the byte sequence, code page,
 * line-break style, and byte order mark of untouched lines match the original.
 *
 * <p>CRLF is normalized to LF before comparison, because the full edited text is passed from the
 * GUI as UTF-8 text and its line-break style may differ from the original's. Restoring the
 * replacement text's line breaks to the original's style is handled by {@link ByteSpliceApplier}.
 */
public final class MinimalLineEdit {

    private MinimalLineEdit() {
    }

    /**
     * The splice result.
     *
     * @param bytes            the fixed byte sequence (in the same code page as the original)
     * @param changed          whether there was a difference. If not, {@code bytes} is the original byte sequence itself
     * @param changedLineFrom  the first differing line (1-based). 0 if {@code changed} is false
     * @param changedLineTo    the last differing line (1-based, inclusive). For a pure line
     *                         insertion this is {@code changedLineFrom - 1}, pointing to the
     *                         line immediately before the insertion point
     */
    public record Result(byte[] bytes, boolean changed, int changedLineFrom, int changedLineTo) {
    }

    /**
     * Returns the byte sequence obtained by applying the full edited text to the original's
     * decoded source. Throws {@link IllegalArgumentException} without returning a byte sequence
     * if the edited text contains a character that cannot be encoded in the original's code page.
     *
     * @param path        the source path attached to edit positions (does not affect the content)
     * @param original    the original's decoded source
     * @param editedText  the full edited text
     */
    public static Result apply(String path, DecodedSource original, String editedText) {
        List<String> originalLines = lines(original.text());
        List<String> editedLines = lines(editedText);
        int prefix = commonPrefix(originalLines, editedLines);
        int suffix = commonSuffix(originalLines, editedLines, prefix);
        int originalEnd = originalLines.size() - suffix;
        int editedEnd = editedLines.size() - suffix;
        if (prefix == originalEnd && prefix == editedEnd) {
            return new Result(original.originalBytes(), false, 0, 0);
        }

        String replacement = String.join("", editedLines.subList(prefix, editedEnd));
        SourcePosition start;
        if (prefix == 0) {
            requireNoLeadingShiftCode(original);
            start = lineStartPosition(path, 1);
        } else {
            // Replace starting from the line break of the preceding line. In EBCDIC, the shift
            // code (SO) that opens DBCS at the start of a line is placed before the byte
            // position of the first character, so starting from the line's first character
            // would leave the original's SO in place while adding the replacement text's own SO,
            // doubling it. Including the line break brings the shift code within the replaced
            // range.
            start = lineBreakPosition(path, original, prefix);
            replacement = "\n" + replacement;
        }
        TextEdit edit = new TextEdit(
                new SourceRange(start, lineStartPosition(path, originalEnd + 1)), replacement);
        requireEncodable(replacement, original.encodingInfo().codePage());
        byte[] bytes = new ByteSpliceApplier().apply(original, List.of(edit));
        return new Result(bytes, true, prefix + 1, originalEnd);
    }

    /**
     * Splits the text while keeping the trailing line break in each line. Because the line break
     * is treated as part of the line, a difference in whether there is a trailing newline shows
     * up as a difference in the last line, and line numbers correspond one-to-one with element
     * positions.
     */
    private static List<String> lines(String text) {
        String normalized = text.replace("\r\n", "\n");
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == '\n') {
                lines.add(normalized.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < normalized.length()) {
            lines.add(normalized.substring(start));
        }
        return lines;
    }

    private static int commonPrefix(List<String> a, List<String> b) {
        int max = Math.min(a.size(), b.size());
        int prefix = 0;
        while (prefix < max && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        return prefix;
    }

    /** The number of matching lines from the end. Capped at the remaining line count so lines already trimmed from the start are not double-counted. */
    private static int commonSuffix(List<String> a, List<String> b, int prefix) {
        int max = Math.min(a.size(), b.size()) - prefix;
        int suffix = 0;
        while (suffix < max
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        return suffix;
    }

    /** The position pointing to the start of the given line (1-based). {@code lineCount+1} points to the end of the text. */
    private static SourcePosition lineStartPosition(String path, int line) {
        return new SourcePosition(path, line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET);
    }

    /**
     * The position pointing to the line-end break of the given line (1-based). For a CRLF line
     * this points to the CR. Because the replacement text's leading line break is restored to the
     * original's line-break style, the line-break style is preserved even when the CR is replaced
     * along with it.
     */
    private static SourcePosition lineBreakPosition(String path, DecodedSource original, int line) {
        ByteOffsetTable table = original.offsetTable();
        int lineStart = table.lineStartCharIndex(line);
        int breakAt = table.lineStartCharIndex(line + 1) - 1;
        if (breakAt > lineStart && original.text().charAt(breakAt - 1) == '\r') {
            breakAt--;
        }
        return new SourcePosition(path, line, breakAt - lineStart + 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
    }

    /**
     * When replacing starting from line 1, confirms there is no byte at the start of the text
     * that produces no character. If the text in EBCDIC starts with a shift code, that byte
     * cannot be included in the replaced range and stays in the original, while the replacement
     * text also carries its own shift code, breaking the write-back. This does not actually occur
     * in fixed format, since line 1 starts with the sequence-number area, but it is rejected
     * rather than writing out a broken result.
     *
     * <p>A UTF-8 byte order mark is also placed at the start of the text, but since the
     * replacement text never produces one, leaving the original's BOM in place is correct.
     * Hence only EBCDIC is checked here.
     */
    private static void requireNoLeadingShiftCode(DecodedSource original) {
        if (original.encodingInfo().codePage().isEbcdic()
                && original.offsetTable().byteOffsetOfChar(0) > 0) {
            throw new IllegalArgumentException(
                    "本文がシフトコードから始まる EBCDIC ソースは、1行目を含む編集を書き戻せない");
        }
    }

    /**
     * Confirms that the replacement text can be encoded in the original's code page.
     *
     * <p>{@code String.getBytes(Charset)} silently replaces unencodable characters with '?' and
     * the like, so on an EBCDIC SBCS plane without full-width characters, say, the original would
     * be rewritten with corrupted content. Here the text is run through an encoder set to
     * {@link CodingErrorAction#REPORT}, and any edit that would trigger a replacement is rejected
     * with an exception.
     */
    private static void requireEncodable(String replacement, CodePage codePage) {
        CharsetEncoder encoder = codePage.charset().newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            encoder.encode(CharBuffer.wrap(replacement));
            return;
        } catch (CharacterCodingException e) {
            // The exception carries no position for the unencodable character, so it is located below by checking one character at a time.
        }
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (!encoder.canEncode(c)) {
                throw new IllegalArgumentException("コードページ " + codePage.charsetName()
                        + " で符号化できない文字が編集後の本文にある: '" + c
                        + "' (U+" + String.format("%04X", (int) c) + ")");
            }
        }
        throw new IllegalArgumentException(
                "コードページ " + codePage.charsetName() + " で符号化できない文字が編集後の本文にある");
    }
}
