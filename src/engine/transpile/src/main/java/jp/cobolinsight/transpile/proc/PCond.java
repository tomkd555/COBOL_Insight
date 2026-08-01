package jp.cobolinsight.transpile.proc;

/**
 * 手続き文の条件式の中間表現。関係比較・論理積・論理和・否定・直訳不能の5種。
 * 言語別の字面(文字列比較の {@code .equals}・論理演算子・否定)は {@link ExprWriter} が
 * {@link ProcedureDialect} を介して与える。
 */
public sealed interface PCond permits PCond.Rel, PCond.And, PCond.Or, PCond.Negate, PCond.Raw {

    /**
     * 直訳不能な条件({@link Raw})の注記を返す。原文を添えて可視化する。直訳できる条件は空文字列。
     * IF・WHILE(inline/out-of-line PERFORM UNTIL/VARYING)の各構築箇所で共通に使う。
     */
    static String untranslatableNote(PCond cond) {
        return cond instanceof Raw raw
                ? "条件に未宣言項目/特殊レジスタを含み直訳不能: " + raw.text()
                : "";
    }

    /** 関係比較。stringCompare が真なら文字列比較(言語により {@code .equals}/{@code compareTo})。 */
    record Rel(PExpr left, RelOp op, PExpr right, boolean stringCompare) implements PCond {
    }

    record And(PCond left, PCond right) implements PCond {
    }

    record Or(PCond left, PCond right) implements PCond {
    }

    record Negate(PCond inner) implements PCond {
    }

    /** 直訳できない条件。原文を保持し、字面は恒真値へ落として注記で可視化する。 */
    record Raw(String text) implements PCond {
    }
}
