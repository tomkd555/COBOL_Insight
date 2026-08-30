package jp.cobolinsight.core.source;

import java.util.Objects;

/** A token annotated with its position in the original source. */
public record PositionedToken(String text, SourceRange range) {

    public PositionedToken {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        Objects.requireNonNull(range, "range");
    }
}
