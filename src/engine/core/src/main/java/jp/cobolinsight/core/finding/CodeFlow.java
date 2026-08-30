package jp.cobolinsight.core.finding;

import java.util.List;

/** A path corresponding to a SARIF codeFlow. Used by findings originating from taint tracking to hold their path. */
public record CodeFlow(List<CodeFlowStep> steps) {

    public CodeFlow {
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }
    }
}
