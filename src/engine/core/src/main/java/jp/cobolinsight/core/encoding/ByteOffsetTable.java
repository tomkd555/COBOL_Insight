package jp.cobolinsight.core.encoding;

/**
 * Table mapping post-decode character positions to original byte offsets.
 * Line numbers are 1-based; the in-line character position (column) is 0-based, in UTF-16 code units.
 */
public final class ByteOffsetTable {

    /** Length charCount+1. The last element is the total length of the original byte array. */
    private final int[] charStartByteOffsets;
    /** Starting character position of each line. A line begins right after a '\n'. */
    private final int[] lineStartCharIndexes;

    ByteOffsetTable(String text, int[] charStartByteOffsets) {
        this.charStartByteOffsets = charStartByteOffsets;
        // A trailing newline does not open a new line. The line count only increases when
        // a character follows the newline.
        int lineCount = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && i + 1 < text.length()) {
                lineCount++;
            }
        }
        this.lineStartCharIndexes = new int[lineCount];
        int line = 1;
        for (int i = 0; i < text.length() && line < lineCount; i++) {
            if (text.charAt(i) == '\n') {
                lineStartCharIndexes[line++] = i + 1;
            }
        }
    }

    public int charCount() {
        return charStartByteOffsets.length - 1;
    }

    public int byteLength() {
        return charStartByteOffsets[charStartByteOffsets.length - 1];
    }

    /** Original byte offset for a character position (0 to charCount). Passing charCount returns the total byte length. */
    public int byteOffsetOfChar(int charIndex) {
        return charStartByteOffsets[charIndex];
    }

    public int lineCount() {
        return lineStartCharIndexes.length;
    }

    /**
     * Starting character position of the given line (1-based). {@code lineCount()+1} refers to
     * just past the last line, i.e. the end of the text — this lets an insertion point at the
     * end be expressed as a (line, column) pair.
     */
    public int lineStartCharIndex(int line) {
        if (line == lineStartCharIndexes.length + 1) {
            return charCount();
        }
        return lineStartCharIndexes[line - 1];
    }

    /** Original byte offset at the start of the given line (1-based). */
    public int lineStartByteOffset(int line) {
        return byteOffsetOfChar(lineStartCharIndex(line));
    }

    /** Original byte offset for the given line (1-based) and in-line character position (0-based). */
    public int byteOffsetAt(int line, int column) {
        return byteOffsetOfChar(lineStartCharIndex(line) + column);
    }
}
