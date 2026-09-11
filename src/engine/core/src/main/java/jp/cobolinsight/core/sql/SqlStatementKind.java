package jp.cobolinsight.core.sql;

/** The kind of an embedded SQL statement, one constant per Db2 for z/OS statement the tool names. */
public enum SqlStatementKind {
    /** A SELECT with no INTO: the query of a cursor, a subquery, a standalone SELECT. */
    SELECT,
    /** A single-row SELECT that reads its columns INTO host variables. */
    SELECT_INTO,
    INSERT,
    UPDATE,
    DELETE,
    MERGE,
    DECLARE_CURSOR,
    OPEN,
    FETCH,
    CLOSE,
    COMMIT,
    ROLLBACK,
    SAVEPOINT,
    RELEASE_SAVEPOINT,
    CALL,
    LOCK_TABLE,
    /** SET, in every form: an assignment to a host variable and a special register alike. */
    SET,
    VALUES_INTO,
    PREPARE,
    EXECUTE,
    EXECUTE_IMMEDIATE,
    DESCRIBE,
    WHENEVER,
    INCLUDE,
    DECLARE_TABLE,
    DECLARE_STATEMENT,
    DECLARE_VARIABLE,
    BEGIN_DECLARE_SECTION,
    END_DECLARE_SECTION,
    GET_DIAGNOSTICS,
    CONNECT,
    TRUNCATE,
    ALLOCATE_CURSOR,
    ASSOCIATE_LOCATORS,
    /** CREATE, ALTER, DROP, GRANT, REVOKE, COMMENT, LABEL and RENAME, which a program rarely runs. */
    DDL,
    /** Any statement not listed above, and a statement that could not be parsed. */
    OTHER
}
