package jp.cobolinsight.engineapi.finding;

import java.util.List;

/** SARIF の codeFlow に対応する経路。汚染追跡由来の finding が経路を保持するために使う。 */
public record CodeFlow(List<CodeFlowStep> steps) {

    public CodeFlow {
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }
    }
}
