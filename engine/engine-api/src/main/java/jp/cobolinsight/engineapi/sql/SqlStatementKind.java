package jp.cobolinsight.engineapi.sql;

/** 埋め込みSQL文の種別。 */
public enum SqlStatementKind {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    DECLARE_CURSOR,
    OPEN,
    FETCH,
    CLOSE,
    OTHER
}
