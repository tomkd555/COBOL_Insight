package jp.cobolinsight.core.jcl;

import jp.cobolinsight.core.source.SourcePosition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An EXEC step. {@code condition} is the textual representation of the step-level COND clause.
 *
 * <p>{@code parameters} holds every keyword of the EXEC statement in the order it was written, a
 * qualifier included ({@code PARM.STEP1}), so an override keeps the step it names; {@code parm} is
 * the PARM value alone with its apostrophes taken off. A step expanded out of a PROC keeps the
 * caller's step name in {@code name} and the name it carries inside the PROC in
 * {@code procStepName}. {@code utility} is what the step's own control cards say it does.
 */
public record JclStep(String name, JclExecKind execKind, String target, Optional<String> condition,
        List<JclDdStatement> ddStatements, Map<String, String> parameters, Optional<String> parm,
        Optional<String> procStepName, Optional<JclUtilityFacts> utility,
        SourcePosition position) {

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
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        Objects.requireNonNull(parm, "parm");
        Objects.requireNonNull(procStepName, "procStepName");
        Objects.requireNonNull(utility, "utility");
        Objects.requireNonNull(position, "position");
    }

    /** A step with nothing but its target and its DD statements: what a caller builds by hand. */
    public JclStep(String name, JclExecKind execKind, String target, Optional<String> condition,
            List<JclDdStatement> ddStatements, SourcePosition position) {
        this(name, execKind, target, condition, ddStatements, Map.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), position);
    }
}
