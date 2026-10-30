package jp.cobolinsight.core.source;

import java.util.Objects;
import java.util.Optional;

/**
 * One line after inline expansion. Holds the originating line (1-based) within the copybook and
 * the text after REPLACING has been applied. The text reflects its shape after preprocessing, so
 * comment lines, the sequence number area, and the identification area become blank.
 *
 * <p>{@code copybookPath} names the copybook a nested COPY brought the line from; it is empty for
 * a line of the copybook the expansion itself names.
 */
public record ExpandedCopyLine(int copybookLine, String text, Optional<String> copybookPath) {

    public ExpandedCopyLine {
        if (copybookLine < 1) {
            throw new IllegalArgumentException("copybookLine must be >= 1: " + copybookLine);
        }
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(copybookPath, "copybookPath");
    }

    /** A line of the copybook the expansion itself names. */
    public ExpandedCopyLine(int copybookLine, String text) {
        this(copybookLine, text, Optional.empty());
    }
}
