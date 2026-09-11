package jp.cobolinsight.core.sql;

/**
 * One assignment of an UPDATE or of the UPDATE branch of a MERGE. value is the source text of the
 * right-hand side with the host variables restored to their original data names.
 */
public record SqlSetPair(String column, String value) {

    public SqlSetPair {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("column must not be blank");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
    }
}
