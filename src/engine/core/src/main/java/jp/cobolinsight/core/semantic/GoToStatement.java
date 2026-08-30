package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourceRange;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A GO TO statement. The DEPENDING ON form has multiple targets and a variable name. targets is
 * allowed to be empty in order to permit the target-unspecified form that ALTER modifies.
 */
public record GoToStatement(List<String> targets, Optional<String> dependingOn, SourceRange range)
        implements Statement {

    public GoToStatement {
        targets = List.copyOf(targets);
        Objects.requireNonNull(dependingOn, "dependingOn");
        Objects.requireNonNull(range, "range");
    }
}
