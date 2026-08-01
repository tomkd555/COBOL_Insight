package jp.cobolinsight.engineapi.callgraph;

/** 呼出関係グラフのエッジ種別。 */
public enum EdgeKind {
    /** プログラム間のCALL、段落・節へのPERFORM。 */
    CALL,
    /** ジョブ/ステップによるプログラム実行(EXEC PGM=)。 */
    EXECUTION,
    /** データセット・Db2表への参照。 */
    REFERENCE,
    /** EXEC CICSのXCTL・LINK・START・RETURN TRANSIDによるトランザクション遷移。 */
    TRANSACTION_TRANSITION,
    /** SEND MAP・RECEIVE MAPによるBMSマップ参照。 */
    MAP_REFERENCE
}
