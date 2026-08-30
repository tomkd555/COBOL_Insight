package jp.cobolinsight.core.semantic;

/** The kind of embedded block: EXEC SQL, and the six targeted EXEC CICS commands. */
public enum EmbeddedBlockKind {
    SQL,
    CICS_SEND_MAP,
    CICS_RECEIVE_MAP,
    CICS_XCTL,
    CICS_LINK,
    CICS_START,
    CICS_RETURN_TRANSID;

    public boolean isCics() {
        return this != SQL;
    }
}
