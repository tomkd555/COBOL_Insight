package jp.cobolinsight.core.finding;

import jp.cobolinsight.core.source.SourcePosition;

import java.util.Objects;

/** One step of codeFlows. message is a supplementary explanation and an empty string is allowed. */
public record CodeFlowStep(SourcePosition position, String message) {

    public CodeFlowStep {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(message, "message");
    }
}
