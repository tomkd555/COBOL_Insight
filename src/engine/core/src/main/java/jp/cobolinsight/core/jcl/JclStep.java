package jp.cobolinsight.core.jcl;

import jp.cobolinsight.core.source.SourcePosition;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** An EXEC step. condition is the textual representation of the step-level COND clause. */
public record JclStep(String name, JclExecKind execKind, String target, Optional<String> condition,
        List<JclDdStatement> ddStatements, SourcePosition position) {

    public JclStep {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(execKind, "execKind");
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        Objects.requireNonNull(condition, "condition");
        ddStatements = List.copyOf(ddStatements);
        Objects.requireNonNull(position, "position");
    }
}
