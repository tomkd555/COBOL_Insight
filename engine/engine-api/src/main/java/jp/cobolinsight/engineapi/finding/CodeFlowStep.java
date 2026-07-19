package jp.cobolinsight.engineapi.finding;

import jp.cobolinsight.engineapi.source.SourcePosition;

import java.util.Objects;

/** codeFlows の1ステップ。message は補足説明で、空文字列を許す。 */
public record CodeFlowStep(SourcePosition position, String message) {

    public CodeFlowStep {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(message, "message");
    }
}
