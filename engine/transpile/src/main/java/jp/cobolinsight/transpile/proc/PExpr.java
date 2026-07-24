package jp.cobolinsight.transpile.proc;

import java.util.List;

/**
 * 手続き文の値式の中間表現。データ参照・リテラル・算術演算子・算術トークン列の4種で、
 * 言語別の字面は {@link ExprWriter} が {@link ProcedureDialect} を介して与える。
 * 添字は1始まりの COBOL 表記のまま保持し、0始まりへの補正は言語側で行う。
 */
public sealed interface PExpr permits PExpr.Ref, PExpr.Lit, PExpr.Op, PExpr.Arith {

    /** データ項目参照。fieldName は生成側フィールド名、isString は文字列型か、subscripts は添字(空なら単純参照)。 */
    record Ref(String fieldName, boolean isString, List<PExpr> subscripts) implements PExpr {
        public Ref {
            subscripts = List.copyOf(subscripts);
        }

        public static Ref scalar(String fieldName, boolean isString) {
            return new Ref(fieldName, isString, List.of());
        }
    }

    /** リテラル。isString が真なら文字列(value は引用符を除いた中身)、偽なら数値(value は原表記)。 */
    record Lit(String value, boolean isString) implements PExpr {
    }

    /** 算術トークン列の演算子・括弧(そのままの字面)。 */
    record Op(String symbol) implements PExpr {
    }

    /** 算術式。オペランド(Ref/Lit)と演算子(Op)を原順で並べたトークン列。 */
    record Arith(List<PExpr> parts) implements PExpr {
        public Arith {
            parts = List.copyOf(parts);
        }
    }
}
