package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourceRange;

import java.util.Objects;

/** A non-nested simple statement. verb is the statement's verb (MOVE, DISPLAY, etc.), text is the full statement text. */
public record SimpleStatement(String verb, String text, SourceRange range) implements Statement {

    public SimpleStatement {
        if (verb == null || verb.isBlank()) {
            throw new IllegalArgumentException("verb must not be blank");
        }
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(range, "range");
    }
}
