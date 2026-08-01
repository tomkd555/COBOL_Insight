package jp.cobolinsight.sqlfrontend;

/** SQL文解析の成否。NOT_ANALYZABLE は解析対象外(理由を statusReason に持つ)。 */
public enum AnalysisStatus {
    ANALYZED,
    NOT_ANALYZABLE
}
