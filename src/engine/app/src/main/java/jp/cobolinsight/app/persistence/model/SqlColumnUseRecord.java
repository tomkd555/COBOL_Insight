package jp.cobolinsight.app.persistence.model;

/**
 * One row of the SQL_COLUMN_USE table: a column one statement refers to. {@code tableName} is the
 * qualifier the query writes, resolved through any correlation name, and is null for an
 * unqualified column.
 */
public record SqlColumnUseRecord(long id, long stmtId, String tableName, String columnName) {

    public SqlColumnUseRecord {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("columnName must not be blank");
        }
    }
}
