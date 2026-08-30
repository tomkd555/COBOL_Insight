package jp.cobolinsight.frontend.sql;

/** Outcome of SQL statement analysis. NOT_ANALYZABLE means not analyzable (the reason is held in statusReason). */
public enum AnalysisStatus {
    ANALYZED,
    NOT_ANALYZABLE
}
