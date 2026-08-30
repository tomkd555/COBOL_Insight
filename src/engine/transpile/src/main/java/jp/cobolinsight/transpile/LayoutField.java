package jp.cobolinsight.transpile;

import jp.cobolinsight.core.picture.PictureType;
import jp.cobolinsight.core.semantic.ConditionName;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One item in a record layout. offset is the cumulative byte offset from the start of the record;
 * byteLength is the stored byte length of one occurrence (for a group item, the sum of its children;
 * for an elementary item, computed from PICTURE+USAGE); occurs is the OCCURS count (1 when not
 * specified). totalSpan() is byteLength x occurs, used to compute the offset of the next same-level
 * sibling. pictureType is present only for elementary items. redefines is the REDEFINES target name.
 * conditionNames are the 88-level condition names attached to this item. children are the direct
 * subitems of a group item (empty for an elementary item). For an OCCURS item, the offset of each
 * child is the absolute offset relative to the first occurrence (index 0); the occurrence at a
 * given index is located at offset + index x byteLength.
 */
public record LayoutField(String name, int level, int offset, int byteLength, int occurs,
        Optional<PictureType> pictureType, Optional<String> redefines,
        List<ConditionName> conditionNames, List<LayoutField> children) {

    public LayoutField {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0: " + offset);
        }
        if (byteLength < 0) {
            throw new IllegalArgumentException("byteLength must be >= 0: " + byteLength);
        }
        if (occurs < 1) {
            throw new IllegalArgumentException("occurs must be >= 1: " + occurs);
        }
        Objects.requireNonNull(pictureType, "pictureType");
        Objects.requireNonNull(redefines, "redefines");
        conditionNames = List.copyOf(conditionNames);
        children = List.copyOf(children);
    }

    /** Total byte length occupied by this item (across all OCCURS occurrences). */
    public int totalSpan() {
        return byteLength * occurs;
    }

    /** Finds one item by name within the subtree rooted at this item (including itself). */
    public Optional<LayoutField> find(String targetName) {
        if (name.equals(targetName)) {
            return Optional.of(this);
        }
        for (LayoutField child : children) {
            Optional<LayoutField> found = child.find(targetName);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
