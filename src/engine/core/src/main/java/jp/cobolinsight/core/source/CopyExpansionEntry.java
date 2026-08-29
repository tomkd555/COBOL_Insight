package jp.cobolinsight.core.source;

/** COPY/REPLACE 展開の対応。展開後の行範囲と、元のコピーブック位置を対応づける。 */
public record CopyExpansionEntry(int expandedStartLine, int expandedEndLine, String copybookPath,
        int copybookStartLine) {

    public CopyExpansionEntry {
        if (expandedStartLine < 1) {
            throw new IllegalArgumentException("expandedStartLine must be >= 1: " + expandedStartLine);
        }
        if (expandedEndLine < expandedStartLine) {
            throw new IllegalArgumentException("expandedEndLine must be >= expandedStartLine: "
                    + expandedStartLine + ".." + expandedEndLine);
        }
        if (copybookPath == null || copybookPath.isBlank()) {
            throw new IllegalArgumentException("copybookPath must not be blank");
        }
        if (copybookStartLine < 1) {
            throw new IllegalArgumentException("copybookStartLine must be >= 1: " + copybookStartLine);
        }
    }
}
