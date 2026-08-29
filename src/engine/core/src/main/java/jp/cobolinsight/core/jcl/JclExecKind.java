package jp.cobolinsight.core.jcl;

/** EXEC文の対象種別。 */
public enum JclExecKind {
    /** EXEC PGM=。 */
    PGM,
    /** EXEC PROC名(カタログ化PROC呼出)。 */
    PROC
}
