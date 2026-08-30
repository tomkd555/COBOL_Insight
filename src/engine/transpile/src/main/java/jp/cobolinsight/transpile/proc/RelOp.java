package jp.cobolinsight.transpile.proc;

/** Relational operator: six kinds normalized from COBOL symbols and word forms. */
public enum RelOp {
    EQ, NE, GT, LT, GE, LE;

    /** The negated relational operator (the equivalent conversion when NOT is prefixed). */
    public RelOp negate() {
        return switch (this) {
            case EQ -> NE;
            case NE -> EQ;
            case GT -> LE;
            case LT -> GE;
            case GE -> LT;
            case LE -> GT;
        };
    }
}
