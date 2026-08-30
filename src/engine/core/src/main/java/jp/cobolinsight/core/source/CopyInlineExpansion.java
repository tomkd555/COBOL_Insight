package jp.cobolinsight.core.source;

import java.util.List;

/**
 * The inline expansion of a single COPY statement. At the position of the original COPY
 * statement, holds which copybook line appears as which text after REPLACING is applied,
 * in expansion order.
 *
 * <p>Whereas {@link CopyExpansionEntry} holds the line range in the expanded source, this type
 * is anchored on the original COPY statement and holds the content of each expanded line. It is
 * used to show what the original looks like with the copybook spliced in.
 */
public record CopyInlineExpansion(int copyStatementLine, String copybookName, String copybookPath,
        List<ExpandedCopyLine> lines) {

    public CopyInlineExpansion {
        if (copyStatementLine < 1) {
            throw new IllegalArgumentException(
                    "copyStatementLine must be >= 1: " + copyStatementLine);
        }
        if (copybookName == null || copybookName.isBlank()) {
            throw new IllegalArgumentException("copybookName must not be blank");
        }
        if (copybookPath == null || copybookPath.isBlank()) {
            throw new IllegalArgumentException("copybookPath must not be blank");
        }
        lines = List.copyOf(lines);
    }
}
