package jp.cobolinsight.core.spi;

/** ルールが依存する解析段階。 */
public enum AnalysisPhase {
    /** 構文のみに依存する(SQL指摘ルールを含む)。 */
    SYNTAX,
    /** 制御フローグラフ、またはBMSマップモデルとEXEC CICS解析結果の突合に依存する。 */
    CONTROL_FLOW,
    /** 手続き間データフローに依存する。 */
    DATA_FLOW;

    /** 画面と CLI が示す日本語の呼び名。 */
    public String label() {
        return switch (this) {
            case SYNTAX -> "構文";
            case CONTROL_FLOW -> "制御フロー";
            case DATA_FLOW -> "データフロー";
        };
    }
}
