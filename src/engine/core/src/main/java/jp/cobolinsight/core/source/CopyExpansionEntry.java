package jp.cobolinsight.core.source;

/** A COPY/REPLACE expansion mapping. Maps a line range in the expanded output to the original copybook position. */
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
