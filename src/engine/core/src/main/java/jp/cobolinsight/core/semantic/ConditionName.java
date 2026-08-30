package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourcePosition;

import java.util.List;
import java.util.Objects;

/** A level-88 condition name. values holds the VALUE clause's values (ranges stay in "low THRU high" form). */
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
