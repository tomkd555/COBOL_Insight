package jp.cobolinsight.core.semantic;

/** Whether a CALL is static or dynamic. */
public enum CallKind {
    /** CALL 'literal'. The target is a program name literal. */
    STATIC,
    /** CALL identifier. The target is the given variable name. */
    DYNAMIC
}
