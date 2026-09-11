package jp.cobolinsight.core.sql;

/** How much of an embedded SQL statement the frontend managed to read. */
public enum SqlAnalysis {
    /** The grammar accepted the statement; every field of the model is trustworthy. */
    FULL,
    /**
     * The grammar did not accept the statement. Only what a keyword scan could recover is
     * filled in: the kind, the referenced tables, the cursor name and the host variables.
     * Structure signals are empty, so the rules that read them have to skip the statement.
     */
    DEGRADED
}
