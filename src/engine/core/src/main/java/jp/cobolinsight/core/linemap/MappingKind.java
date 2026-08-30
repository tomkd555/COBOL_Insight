package jp.cobolinsight.core.linemap;

/** The kind of line mapping. Represents the correspondence between COBOL-side and generated-side line counts. */
public enum MappingKind {
    /** One COBOL line corresponds to one generated line. */
    ONE_TO_ONE,
    /** One COBOL line corresponds to multiple generated lines. */
    ONE_TO_MANY,
    /** Multiple COBOL lines are folded into one generated line. */
    MANY_TO_ONE
}
