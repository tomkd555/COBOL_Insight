package jp.cobolinsight.core.source;

import java.util.Objects;

/**
 * One line after inline expansion. Holds the originating line (1-based) within the copybook and
 * the text after REPLACING has been applied. The text reflects its shape after preprocessing, so
 * comment lines, the sequence number area, and the identification area become blank.
 */
public record ExpandedCopyLine(int copybookLine, String text) {

    public ExpandedCopyLine {
        if (copybookLine < 1) {
            throw new IllegalArgumentException("copybookLine must be >= 1: " + copybookLine);
        }
        Objects.requireNonNull(text, "text");
    }
}
