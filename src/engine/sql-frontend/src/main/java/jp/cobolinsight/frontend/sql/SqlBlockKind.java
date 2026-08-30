package jp.cobolinsight.frontend.sql;

/** The kind of an EXEC SQL block. */
public enum SqlBlockKind {
    INCLUDE,
    BEGIN_DECLARE_SECTION,
    END_DECLARE_SECTION,
    DECLARE_CURSOR,
    WHENEVER,
    EXECUTABLE
}
