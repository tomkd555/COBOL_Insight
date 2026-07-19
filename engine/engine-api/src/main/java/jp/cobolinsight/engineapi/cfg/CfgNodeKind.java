package jp.cobolinsight.engineapi.cfg;

/** CFGノードの種別。 */
public enum CfgNodeKind {
    /** プログラム入口。文を持たない。 */
    ENTRY,
    /** プログラム出口。文を持たない。 */
    EXIT,
    /** 手続き部の1文(複合文は条件ノードとして本体の文と別に持つ)。 */
    STATEMENT
}
