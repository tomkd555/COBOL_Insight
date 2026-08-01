package jp.cobolinsight.engineapi.semantic;

/** 埋め込みブロックの種別。EXEC SQL と、対象とする EXEC CICS コマンド6種。 */
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
