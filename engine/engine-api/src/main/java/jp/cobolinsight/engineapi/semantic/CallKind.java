package jp.cobolinsight.engineapi.semantic;

/** CALL の静的・動的の別。 */
public enum CallKind {
    /** CALL 'literal'。target はプログラム名リテラル。 */
    STATIC,
    /** CALL identifier。target は指定変数名。 */
    DYNAMIC
}
