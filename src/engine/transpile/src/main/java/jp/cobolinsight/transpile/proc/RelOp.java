package jp.cobolinsight.transpile.proc;

/** 関係演算子。COBOL の記号・語形を正規化した6種。 */
public enum RelOp {
    EQ, NE, GT, LT, GE, LE;

    /** 否定した関係演算子(NOT を前置した場合の等価変換)。 */
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
