package jp.cobolinsight.sqlfrontend;

/** EXEC SQL ブロックの種別。 */
public enum SqlBlockKind {
    INCLUDE,
    BEGIN_DECLARE_SECTION,
    END_DECLARE_SECTION,
    DECLARE_CURSOR,
    WHENEVER,
    EXECUTABLE
}
