package jp.cobolinsight.core.sql;

/**
 * One column of a DECLARE TABLE or a CREATE TABLE. type is the data type as the statement writes
 * it, and is blank only where the statement gives none. nullable is false when NOT NULL stands on
 * the column.
 */
public record SqlDeclaredColumn(String name, String type, boolean nullable) {

    public SqlDeclaredColumn {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
    }
}
