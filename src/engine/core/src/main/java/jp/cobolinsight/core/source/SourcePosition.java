package jp.cobolinsight.core.source;

/**
 * 原ソース上の1点。行・桁は1始まり。byteOffset は原バイト列上のオフセットで、不明の場合は
 * {@link #UNKNOWN_BYTE_OFFSET}。
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

    /** ファイル先頭(行1・桁1)を指す位置。行・桁を特定できない場合に用いる。 */
    public static SourcePosition fileStart(String file) {
        return new SourcePosition(file, 1, 1, UNKNOWN_BYTE_OFFSET);
    }
}
