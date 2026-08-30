package jp.cobolinsight.core.source;

/**
 * A single point in the original source. Line and column are 1-based. byteOffset is the offset
 * in the original byte array, or {@link #UNKNOWN_BYTE_OFFSET} when unknown.
 */
public record SourcePosition(String file, int line, int column, int byteOffset) {

    public static final int UNKNOWN_BYTE_OFFSET = -1;

    public SourcePosition {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("file must not be blank");
        }
        if (line < 1) {
            throw new IllegalArgumentException("line must be >= 1: " + line);
        }
        if (column < 1) {
            throw new IllegalArgumentException("column must be >= 1: " + column);
        }
        if (byteOffset < UNKNOWN_BYTE_OFFSET) {
            throw new IllegalArgumentException("byteOffset must be >= -1: " + byteOffset);
        }
    }

    /** A position pointing to the start of the file (line 1, column 1). Used when the line and column cannot be determined. */
    public static SourcePosition fileStart(String file) {
        return new SourcePosition(file, 1, 1, UNKNOWN_BYTE_OFFSET);
    }
}
