package jp.cobolinsight.core.source;

import java.util.Objects;

/** 原ソース座標付きトークン。 */
public record PositionedToken(String text, SourceRange range) {

    public PositionedToken {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        Objects.requireNonNull(range, "range");
    }
}
