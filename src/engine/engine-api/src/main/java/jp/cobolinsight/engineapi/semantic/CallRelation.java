package jp.cobolinsight.engineapi.semantic;

import jp.cobolinsight.engineapi.source.SourceRange;

import java.util.Objects;

/** プログラム間のCALL関係。 */
public record CallRelation(String callerProgramId, CallKind kind, String target, SourceRange range) {

    public CallRelation {
        if (callerProgramId == null || callerProgramId.isBlank()) {
            throw new IllegalArgumentException("callerProgramId must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        Objects.requireNonNull(range, "range");
    }
}
