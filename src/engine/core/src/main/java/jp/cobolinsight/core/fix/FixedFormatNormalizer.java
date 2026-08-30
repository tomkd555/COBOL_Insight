package jp.cobolinsight.core.fix;

import jp.cobolinsight.core.source.FixedFormatColumns;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * A normalizer that lays out an inserted statement to fit fixed-format column rules.
 *
 * <p>Columns are 1-based. The sequence-number field (columns 1-6), the indicator area at
 * column 7, and area A (columns 8-11) are left blank, and the statement text is laid out
 * starting at column 12, the start of area B. When the statement text exceeds column 72, the
 * default is to <b>wrap at word boundaries (spaces)</b>. This wrapping places no continuation
 * indicator; it treats the line break as a word separator (a space) and sends the next word to a
 * new physical line starting at area B. Wrapping therefore never joins adjacent words together.
 *
 * <p>Only when a single word or literal cannot by itself fit into area B (columns 12-72) is it
 * split mid-way onto a continuation line (with {@code -} in column 7). When splitting a literal
 * mid-way, an opening quote is reinserted at the start of area B on the continuation line, per
 * COBOL's continuation rules. A physical line at a mid-split point is padded with statement text
 * out to exactly column 72, because the compiler treats a line-end field short of column 72 as
 * blank, and that blank padding would otherwise be mixed into the literal's value when the
 * literal is split mid-way.
 *
 * <p>Column calculations are done in bytes relative to the given {@link Charset} argument. Even
 * for the same character, the number of characters that fit on one line differs between
 * Shift_JIS (2 bytes per full-width character) and UTF-8 (3 bytes per full-width character).
 * Full-width characters are never split.
 *
 * <p>Since the leading 11 columns are ASCII spaces (1 byte each), the byte budget for statement
 * text that fits into area B (columns 12-72) is {@code 72 - 11 = 61} bytes.
 */
public final class FixedFormatNormalizer {

    /** The starting column of area B (1-based). The canonical column numbers live in {@link FixedFormatColumns}. */
    public static final int B_AREA_START_COLUMN = FixedFormatColumns.AREA_B_START;
    /** The last column that can hold statement text (1-based). Column 73 onward is the identification field and holds no statement text. */
    public static final int CONTENT_END_COLUMN = FixedFormatColumns.CONTENT_END;
    /** The continuation indicator for the column-7 indicator field. */
    private static final char CONTINUATION_INDICATOR = '-';

    /** The prefix for a physical line starting at area B (columns 1-11 blank). No continuation indicator is placed. */
    private static final String B_AREA_PREFIX = " ".repeat(B_AREA_START_COLUMN - 1);
    /** The prefix for a continuation line that mid-splits a word/literal (continuation indicator at column 7). */
    private static final String CONTINUATION_PREFIX =
            " ".repeat(6) + CONTINUATION_INDICATOR + " ".repeat(B_AREA_START_COLUMN - 1 - 7);
    /** The byte budget for statement text that fits into area B (columns 12-72). */
    private static final int B_AREA_BYTE_BUDGET = CONTENT_END_COLUMN - (B_AREA_START_COLUMN - 1);

    /**
     * Returns one or more physical lines laid out into area B (line terminators not included).
     * Text exceeding 72 bytes wraps at word boundaries; only a word or literal that cannot fit
     * by itself is split mid-way onto a continuation line.
     */
    public List<String> layoutStatement(String statement, Charset charset) {
        return layoutStatement(statement, charset, 0);
    }

    /**
     * Lays out text on the assumption that the caller will append {@code reservedTrailingBytes}
     * bytes to the end after layout. The reserved amount is subtracted from the budget of the
     * line carrying the final token, so that even if the caller appends something like a
     * terminating period to the end of the last line, it will not exceed column 72. The
     * reservation applies only to the line carrying the final token; earlier lines still use the
     * full area B. A word or literal that cannot fit by itself and is split mid-way onto a
     * continuation line is not subject to the reservation.
     */
    public List<String> layoutStatement(String statement, Charset charset,
            int reservedTrailingBytes) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentBytes = 0;

        List<String> tokens = tokenize(statement);
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            int budget = index == tokens.size() - 1
                    ? B_AREA_BYTE_BUDGET - reservedTrailingBytes : B_AREA_BYTE_BUDGET;
            int tokenBytes = byteLength(token, charset);
            if (currentBytes == 0) {
                if (tokenBytes <= budget) {
                    current.append(token);
                    currentBytes = tokenBytes;
                } else {
                    splitOversizedToken(token, charset, lines);
                }
            } else if (currentBytes + 1 + tokenBytes <= budget) {
                current.append(' ').append(token);
                currentBytes += 1 + tokenBytes;
            } else {
                lines.add(B_AREA_PREFIX + current);
                current.setLength(0);
                currentBytes = 0;
                if (tokenBytes <= budget) {
                    current.append(token);
                    currentBytes = tokenBytes;
                } else {
                    splitOversizedToken(token, charset, lines);
                }
            }
        }
        if (currentBytes > 0) {
            lines.add(B_AREA_PREFIX + current);
        }
        return lines;
    }

    /**
     * Splits the text into a list of tokens separated by spaces. A quote-enclosed literal,
     * including any internal spaces, is treated as a single token and is never broken apart by
     * wrapping. A doubled quote ({@code ''} / {@code ""}) is treated as an escape inside the
     * literal.
     */
    private static List<String> tokenize(String statement) {
        List<String> tokens = new ArrayList<>();
        int n = statement.length();
        int i = 0;
        while (i < n) {
            while (i < n && statement.charAt(i) == ' ') {
                i++;
            }
            if (i >= n) {
                break;
            }
            int start = i;
            char quote = 0;
            while (i < n) {
                char c = statement.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        if (i + 1 < n && statement.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        quote = 0;
                    }
                    i++;
                    continue;
                }
                if (c == ' ') {
                    break;
                }
                if (c == '\'' || c == '"') {
                    quote = c;
                }
                i++;
            }
            tokens.add(statement.substring(start, i));
        }
        return tokens;
    }

    private void splitOversizedToken(String token, Charset charset, List<String> lines) {
        if (token.charAt(0) == '\'' || token.charAt(0) == '"') {
            splitLiteral(token, charset, lines);
        } else {
            splitWord(token, charset, lines);
        }
    }

    /** Splits a word mid-way onto continuation lines at character boundaries. The first physical line starts at a word boundary, so no continuation indicator is placed. */
    private static void splitWord(String word, Charset charset, List<String> lines) {
        StringBuilder piece = new StringBuilder();
        int pieceBytes = 0;
        boolean first = true;
        int i = 0;
        while (i < word.length()) {
            int codePoint = word.codePointAt(i);
            String unit = new String(Character.toChars(codePoint));
            int unitBytes = byteLength(unit, charset);
            if (pieceBytes > 0 && pieceBytes + unitBytes > B_AREA_BYTE_BUDGET) {
                lines.add((first ? B_AREA_PREFIX : CONTINUATION_PREFIX) + piece);
                first = false;
                piece.setLength(0);
                pieceBytes = 0;
            }
            piece.append(unit);
            pieceBytes += unitBytes;
            i += Character.charCount(codePoint);
        }
        lines.add((first ? B_AREA_PREFIX : CONTINUATION_PREFIX) + piece);
    }

    /**
     * Splits a literal mid-way onto continuation lines. Reinserts an opening quote at the start
     * of area B on the continuation line, and pads a mid-split line out to exactly column 72.
     * The first physical line starts at a word boundary, so no continuation indicator is placed.
     */
    private static void splitLiteral(String literal, Charset charset, List<String> lines) {
        String quote = String.valueOf(literal.charAt(0));
        int quoteBytes = byteLength(quote, charset);
        StringBuilder piece = new StringBuilder();
        int pieceBytes = 0;
        boolean first = true;
        int i = 0;
        while (i < literal.length()) {
            int codePoint = literal.codePointAt(i);
            String unit = new String(Character.toChars(codePoint));
            int unitBytes = byteLength(unit, charset);
            if (pieceBytes + unitBytes > B_AREA_BYTE_BUDGET) {
                lines.add((first ? B_AREA_PREFIX : CONTINUATION_PREFIX) + piece);
                first = false;
                piece.setLength(0);
                piece.append(quote);
                pieceBytes = quoteBytes;
            }
            piece.append(unit);
            pieceBytes += unitBytes;
            i += Character.charCount(codePoint);
        }
        lines.add((first ? B_AREA_PREFIX : CONTINUATION_PREFIX) + piece);
    }

    private static int byteLength(String text, Charset charset) {
        return text.getBytes(charset).length;
    }
}
