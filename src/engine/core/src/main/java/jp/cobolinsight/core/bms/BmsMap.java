package jp.cobolinsight.core.bms;

import java.util.List;
import java.util.Optional;

/** A map defined by DFHMDI. Holds the name, size, and the set of fields it contains. */
public record BmsMap(String name, int sizeRows, int sizeCols, List<BmsField> fields) {

    public BmsMap {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (sizeRows < 1) {
            throw new IllegalArgumentException("sizeRows must be >= 1: " + sizeRows);
        }
        if (sizeCols < 1) {
            throw new IllegalArgumentException("sizeCols must be >= 1: " + sizeCols);
        }
        fields = List.copyOf(fields);
    }

    /** Returns the first field whose name matches. If multiple fields share the same name, returns the first one. */
    public Optional<BmsField> field(String fieldName) {
        return fields.stream().filter(f -> f.name().equals(fieldName)).findFirst();
    }
}
