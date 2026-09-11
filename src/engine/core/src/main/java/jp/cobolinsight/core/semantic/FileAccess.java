package jp.cobolinsight.core.semantic;

/** How a program opens a file: the mode of its OPEN, or the side a SORT or MERGE puts it on. */
public enum FileAccess {
    /** OPEN INPUT, or the USING side of a SORT or MERGE. */
    INPUT,
    /** OPEN OUTPUT, or the GIVING side of a SORT or MERGE. */
    OUTPUT,
    /** OPEN I-O. */
    IO,
    /** OPEN EXTEND. */
    EXTEND
}
