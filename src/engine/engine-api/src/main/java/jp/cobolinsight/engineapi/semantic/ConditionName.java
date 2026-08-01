package jp.cobolinsight.engineapi.semantic;

import jp.cobolinsight.engineapi.source.SourcePosition;

import java.util.List;
import java.util.Objects;

/** 88レベルの条件名。values は VALUE句の値(範囲は "low THRU high" 形式のまま)を保持する。 */
public record ConditionName(String name, List<String> values, SourcePosition position) {

    public ConditionName {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        values = List.copyOf(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        Objects.requireNonNull(position, "position");
    }
}
