package jp.cobolinsight.core.dataflow;

/** 汚染追跡の種別。 */
public enum TaintKind {
    /** 画面・帳票などの外部入力由来(ACCEPT・CICS RECEIVE)。R020 が消費する。 */
    EXTERNAL_INPUT,
    /** 機密データ(名称末尾が -SSN・-ACCT-NO・-CARD-NO)由来。R027 が消費する。 */
    SENSITIVE
}
