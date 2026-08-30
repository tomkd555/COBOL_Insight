package jp.cobolinsight.core.source;

/**
 * The canonical column boundaries of COBOL fixed format. Column numbers are 1-based; a caller
 * that needs 0-based numbers derives them by subtracting {@code -1}. The origin conversion is
 * confined to a single line at each call site, so the same physical quantity is never carried in
 * more than one origin.
 *
 * <p>The values here are the fixed-format regions defined by the COBOL language standard, not
 * numbers this tool chose on its own.
 */
public final class FixedFormatColumns {

    /** The start column of the sequence number area. */
    public static final int SEQUENCE_START = 1;
    /** The end column of the sequence number area. */
    public static final int SEQUENCE_END = 6;
    /** The column of the indicator area. Holds comment (*), page eject (/), or continuation (-). */
    public static final int INDICATOR_COLUMN = 7;
    /** The start column of Area A. The content begins here. */
    public static final int AREA_A_START = 8;
    /** The end column of Area A. */
    public static final int AREA_A_END = 11;
    /** The start column of Area B. */
    public static final int AREA_B_START = 12;
    /** The last column that can hold content. */
    public static final int CONTENT_END = 72;
    /** The start column of the identification area. Everything from here on is not content. */
    public static final int IDENTIFICATION_START = 73;

    private FixedFormatColumns() {
    }

    /**
     * The character in the indicator area (column 7). Returns a space if the line does not reach
     * that far. Columns are counted by character position, not byte position, so the caller must
     * have decoded the line in a way that keeps its columns aligned.
     */
    public static char indicator(String line) {
        return line.length() < INDICATOR_COLUMN ? ' ' : line.charAt(INDICATOR_COLUMN - 1);
    }

    /**
     * The content (columns 8-72). Returns only the range the line covers if it is short, and an
     * empty string for a line that does not reach column 8. The sequence number area and the
     * identification area are dropped, so the head of the return value corresponds to column 8.
     */
    public static String body(String line) {
        if (line.length() < AREA_A_START) {
            return "";
        }
        return line.substring(AREA_A_START - 1, Math.min(line.length(), CONTENT_END));
    }
}
