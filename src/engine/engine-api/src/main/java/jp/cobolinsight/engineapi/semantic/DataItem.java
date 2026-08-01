package jp.cobolinsight.engineapi.semantic;

import jp.cobolinsight.engineapi.source.SourcePosition;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * データ部の項目。レベルは 1〜49・66・77(88レベルは {@link ConditionName} として保持)。
 * children により集団項目と基本項目の親子関係を表す。
 */
public record DataItem(int level, String name, Optional<String> picture, Optional<String> usage,
        Optional<String> value, Optional<String> redefines, Optional<Occurs> occurs,
        List<ConditionName> conditionNames, List<DataItem> children, SourcePosition position) {

    public DataItem {
        if (!((level >= 1 && level <= 49) || level == 66 || level == 77)) {
            throw new IllegalArgumentException("level must be 1..49, 66 or 77: " + level);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(redefines, "redefines");
        Objects.requireNonNull(occurs, "occurs");
        conditionNames = List.copyOf(conditionNames);
        children = List.copyOf(children);
        Objects.requireNonNull(position, "position");
    }
}
