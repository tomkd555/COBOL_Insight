package jp.cobolinsight.frontend.sql;

/** SQL文の種別。 */
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
