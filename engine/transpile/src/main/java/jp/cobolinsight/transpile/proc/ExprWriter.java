package jp.cobolinsight.transpile.proc;

/**
 * 値式・条件式の中間表現を対象言語の字面へ変換する。演算子や添字補正など言語共通の骨格を担い、
 * データ参照の接頭辞・文字列比較・論理演算子など言語差は {@link ProcedureDialect} に委ねる。
 */
public final class ExprWriter {

    private ExprWriter() {
    }

    public static String expr(PExpr e, ProcedureDialect d) {
        return switch (e) {
            case PExpr.Ref ref -> {
                StringBuilder sb = new StringBuilder(d.ref(ref.fieldName()));
                for (PExpr subscript : ref.subscripts()) {
                    sb.append(d.subscript(expr(subscript, d)));
                }
                yield sb.toString();
            }
            case PExpr.Lit lit -> lit.isString() ? d.stringLiteral(lit.value()) : lit.value();
            case PExpr.Op op -> op.symbol();
            case PExpr.Arith arith -> {
                StringBuilder sb = new StringBuilder();
                for (PExpr part : arith.parts()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(expr(part, d));
                }
                yield sb.toString().replace("( ", "(").replace(" )", ")");
            }
        };
    }

    /** 式が文字列型を表すか(DISPLAY の連結や比較の型判定に使う)。 */
    public static boolean isString(PExpr e) {
        return switch (e) {
            case PExpr.Ref ref -> ref.isString();
            case PExpr.Lit lit -> lit.isString();
            case PExpr.Op ignored -> false;
            case PExpr.Arith ignored -> false;
        };
    }

    public static String cond(PCond c, ProcedureDialect d) {
        return switch (c) {
            case PCond.Rel rel ->
                    d.comparison(expr(rel.left(), d), rel.op(), expr(rel.right(), d),
                            rel.stringCompare());
            case PCond.And and -> d.logicalAnd(cond(and.left(), d), cond(and.right(), d));
            case PCond.Or or -> d.logicalOr(cond(or.left(), d), cond(or.right(), d));
            case PCond.Negate neg -> d.negate(cond(neg.inner(), d));
            case PCond.Raw raw -> d.rawCondition(raw.text());
        };
    }
}
