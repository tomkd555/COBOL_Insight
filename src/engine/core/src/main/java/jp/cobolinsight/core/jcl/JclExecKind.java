package jp.cobolinsight.core.jcl;

/** The target kind of an EXEC statement. */
public enum JclExecKind {
    /** EXEC PGM=. */
    PGM,
    /** EXEC PROC name (invocation of a cataloged PROC). */
    PROC
}
