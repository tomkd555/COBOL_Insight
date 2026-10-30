package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourceRange;

import java.util.Objects;
import java.util.Optional;

/**
 * A PERFORM relationship to a paragraph or section. Optionally holds the end paragraph of a THRU
 * range. {@code targetSection} and {@code thruSection} are the sections a {@code P OF S} or
 * {@code P IN S} names, empty where the name is written unqualified.
 */
public record PerformRelation(String fromProcedure, String targetProcedure,
        Optional<String> thruProcedure, SourceRange range, Optional<String> targetSection,
        Optional<String> thruSection) {

    public PerformRelation {
        if (fromProcedure == null || fromProcedure.isBlank()) {
            throw new IllegalArgumentException("fromProcedure must not be blank");
        }
        if (targetProcedure == null || targetProcedure.isBlank()) {
            throw new IllegalArgumentException("targetProcedure must not be blank");
        }
        Objects.requireNonNull(thruProcedure, "thruProcedure");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(targetSection, "targetSection");
        Objects.requireNonNull(thruSection, "thruSection");
    }

    /** A relation whose names carry no section qualifier. */
    public PerformRelation(String fromProcedure, String targetProcedure,
            Optional<String> thruProcedure, SourceRange range) {
        this(fromProcedure, targetProcedure, thruProcedure, range, Optional.empty(),
                Optional.empty());
    }
}
