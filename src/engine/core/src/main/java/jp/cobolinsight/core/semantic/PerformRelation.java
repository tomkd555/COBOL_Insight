package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourceRange;

import java.util.Objects;
import java.util.Optional;

/** A PERFORM relationship to a paragraph or section. Optionally holds the end paragraph of a THRU range. */
public record PerformRelation(String fromProcedure, String targetProcedure,
        Optional<String> thruProcedure, SourceRange range) {

    public PerformRelation {
        if (fromProcedure == null || fromProcedure.isBlank()) {
            throw new IllegalArgumentException("fromProcedure must not be blank");
        }
        if (targetProcedure == null || targetProcedure.isBlank()) {
            throw new IllegalArgumentException("targetProcedure must not be blank");
        }
        Objects.requireNonNull(thruProcedure, "thruProcedure");
        Objects.requireNonNull(range, "range");
    }
}
