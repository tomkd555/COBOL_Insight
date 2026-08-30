package jp.cobolinsight.core.finding;

import jp.cobolinsight.core.source.SourceRange;

import java.util.Objects;

/** A minimal edit that replaces a source range. An empty string in replacement represents a deletion. */
public record TextEdit(SourceRange range, String replacement) {

    public TextEdit {
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(replacement, "replacement");
    }
}
