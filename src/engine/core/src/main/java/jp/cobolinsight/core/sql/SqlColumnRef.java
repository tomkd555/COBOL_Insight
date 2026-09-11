package jp.cobolinsight.core.sql;

import java.util.Objects;
import java.util.Optional;

/**
 * One column reference of a statement. table holds the qualifier the query writes, with a
 * correlation name resolved to the table its FROM clause defines; it is empty for an unqualified
 * column.
 */
public record SqlColumnRef(Optional<String> table, String column) {

    public SqlColumnRef {
        Objects.requireNonNull(table, "table");
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("column must not be blank");
        }
    }
}
