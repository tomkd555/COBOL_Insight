package jp.cobolinsight.frontend.sql;

/** The kind of a SQL statement. */
public enum SqlStatementKind {
    SELECT,
    SELECT_INTO,
    INSERT,
    UPDATE,
    DELETE,
    DECLARE_CURSOR,
    OPEN_CURSOR,
    FETCH,
    CLOSE_CURSOR,
    OTHER
}
