package jp.cobolinsight.app.persistence.model;

/**
 * One row of the SQL_TABLE_USE table: a table one statement names, with the letters R, C, U and D
 * it accesses the table with, in that order.
 */
public record SqlTableUseRecord(long id, long stmtId, String tableName, String access) {

    public SqlTableUseRecord {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        if (access == null || access.isBlank()) {
            throw new IllegalArgumentException("access must not be blank");
        }
    }
}
