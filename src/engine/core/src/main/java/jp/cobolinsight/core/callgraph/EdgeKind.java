package jp.cobolinsight.core.callgraph;

/** Edge kinds of the call graph. */
public enum EdgeKind {
    /**
     * A CALL between programs, a PERFORM to a paragraph/section, an EXEC CICS LINK (which returns
     * to its caller), or an SQL CALL of a stored procedure.
     */
    CALL,
    /** Program execution by a job/step (EXEC PGM=). */
    EXECUTION,
    /** A reference to a dataset or Db2 table. */
    REFERENCE,
    /** A transaction transition via EXEC CICS XCTL, START, or RETURN TRANSID. */
    TRANSACTION_TRANSITION,
    /** A BMS map reference via SEND MAP or RECEIVE MAP. */
    MAP_REFERENCE
}
