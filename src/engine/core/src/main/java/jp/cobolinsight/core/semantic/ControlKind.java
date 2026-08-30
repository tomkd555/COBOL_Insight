package jp.cobolinsight.core.semantic;

/** The control kind of a compound statement. */
public enum ControlKind {
    /** A branch such as IF or EVALUATE. */
    BRANCH,
    /** A loop such as PERFORM UNTIL. */
    LOOP
}
