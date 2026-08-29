package jp.cobolinsight.core.callgraph;

/** エッジの解決根拠。 */
public enum Resolution {
    /** リテラル・定数由来で確定した。 */
    CONSTANT,
    /** 定数伝播・def-use・値集合のデータフロー解析で解決した。 */
    DATAFLOW,
    /** 解決できなかった(接続先は未解決ノード)。 */
    UNRESOLVED
}
