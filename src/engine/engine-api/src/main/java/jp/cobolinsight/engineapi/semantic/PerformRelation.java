package jp.cobolinsight.engineapi.semantic;

import jp.cobolinsight.engineapi.source.SourceRange;

import java.util.Objects;
import java.util.Optional;

/** 段落・節へのPERFORM関係。THRU範囲の終端段落を任意で持つ。 */
public record PerformRelation(String fromProcedure, String targetProcedure,
        Optional<String> thruProcedure, SourceRange range) {

    public PerformRelation {
        if (fromProcedure == null || fromProcedure.isBlank()) {
            throw new IllegalArgumentException("fromProcedure must not be blank");
        }
        if (targetProcedure == null || targetProcedure.isBlank()) {
            throw new IllegalArgumentException("targetProcedure must not be blank");
        }
        Objects.requireNonNull(thruProcedure, "thruProcedure");
        Objects.requireNonNull(range, "range");
    }
}
