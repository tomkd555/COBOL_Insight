package jp.cobolinsight.engineapi.spi;

/** ルールが依存する解析段階。 */
public enum AnalysisPhase {
    /** 構文のみに依存する(SQL助言ルールを含む)。 */
    SYNTAX,
    /** 制御フローグラフ、またはBMSマップモデルとEXEC CICS解析結果の突合に依存する。 */
    CONTROL_FLOW,
    /** 手続き間データフローに依存する。 */
    DATA_FLOW
}
