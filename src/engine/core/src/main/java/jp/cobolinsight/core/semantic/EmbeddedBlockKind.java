package jp.cobolinsight.core.semantic;

/**
 * The kind of embedded block: EXEC SQL, the EXEC CICS commands a rule tells apart, and
 * {@link #CICS_OTHER} for every other EXEC CICS command, so that a rule reading the operands of
 * a command (RESP, NOHANDLE) sees all of them.
 */
public enum EmbeddedBlockKind {
    SQL,
    CICS_SEND_MAP,
    CICS_RECEIVE_MAP,
    CICS_XCTL,
    CICS_LINK,
    CICS_START,
    CICS_RETURN_TRANSID,
    /** RETURN without TRANSID: the end of a transaction, or the return from a LINK. */
    CICS_RETURN,
    CICS_HANDLE_CONDITION,
    CICS_OTHER;

    public boolean isCics() {
        return this != SQL;
    }
}
