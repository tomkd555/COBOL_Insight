package jp.cobolinsight.engineapi.semantic;

import jp.cobolinsight.engineapi.source.SourceRange;

import java.util.Objects;

/** 入れ子を持たない単文。verb は文の動詞(MOVE・DISPLAY など)、text は文の全文。 */
public record SimpleStatement(String verb, String text, SourceRange range) implements Statement {

    public SimpleStatement {
        if (verb == null || verb.isBlank()) {
            throw new IllegalArgumentException("verb must not be blank");
        }
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(range, "range");
    }
}
