package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourceRange;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A GO TO statement. The DEPENDING ON form has multiple targets and a variable name. targets is
 * allowed to be empty in order to permit the target-unspecified form that ALTER modifies.
 * {@code targetSections} runs parallel to {@code targets}: the section a {@code P OF S} or
 * {@code P IN S} names, empty where the target is written unqualified.
 */
public record GoToStatement(List<String> targets, Optional<String> dependingOn, SourceRange range,
        List<Optional<String>> targetSections) implements Statement {

    public GoToStatement {
        targets = List.copyOf(targets);
        Objects.requireNonNull(dependingOn, "dependingOn");
        Objects.requireNonNull(range, "range");
        targetSections = List.copyOf(targetSections);
        if (targetSections.size() != targets.size()) {
            throw new IllegalArgumentException("targetSections must run parallel to targets: "
                    + targetSections.size() + " for " + targets.size());
        }
    }

    /** A GO TO whose targets carry no section qualifier. */
    public GoToStatement(List<String> targets, Optional<String> dependingOn, SourceRange range) {
        this(targets, dependingOn, range,
                Collections.nCopies(targets.size(), Optional.<String>empty()));
    }
}
