package jp.cobolinsight.core.source;

/** 1始まりの行範囲(両端を含む)。 */
public record LineRange(int startLine, int endLine) {

    public LineRange {
        if (startLine < 1) {
            throw new IllegalArgumentException("startLine must be >= 1: " + startLine);
        }
        if (endLine < startLine) {
            throw new IllegalArgumentException(
                    "endLine must be >= startLine: " + startLine + ".." + endLine);
        }
    }
}
