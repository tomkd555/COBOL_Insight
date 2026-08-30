package jp.cobolinsight.core.sql;

/** The kind of an embedded SQL statement. */
public enum SqlStatementKind {
    /** SELECT. Includes both a standalone SELECT and SELECT INTO. */
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    DECLARE_CURSOR,
    OPEN,
    FETCH,
    CLOSE,
    /** Any statement not listed above, and a statement that could not be parsed. */
    OTHER
}
