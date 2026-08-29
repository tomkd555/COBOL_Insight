package jp.cobolinsight.core.semantic;

/** 複合文の制御種別。 */
public enum ControlKind {
    /** IF・EVALUATE などの分岐。 */
    BRANCH,
    /** PERFORM UNTIL などの反復。 */
    LOOP
}
