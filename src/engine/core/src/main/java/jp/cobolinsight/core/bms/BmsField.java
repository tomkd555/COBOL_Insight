package jp.cobolinsight.core.bms;

import java.util.Objects;

/**
 * A field defined by DFHMDF. Position is a 1-based row and column. A literal field with no
 * name uses an empty string for name. attributes is the text representation of the ATTRB clause.
 */
public record BmsField(String name, int row, int column, int length, String attributes) {

    public BmsField {
        Objects.requireNonNull(name, "name");
        if (row < 1) {
            throw new IllegalArgumentException("row must be >= 1: " + row);
        }
        if (column < 1) {
            throw new IllegalArgumentException("column must be >= 1: " + column);
        }
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0: " + length);
        }
        Objects.requireNonNull(attributes, "attributes");
    }
}
