package jp.cobolinsight.transpile.proc;

/**
 * Converts the intermediate representation of value and condition expressions into the target
 * language's textual form. Handles the language-common skeleton, such as operators and subscript
 * adjustment, while delegating language-specific differences—data reference prefixes, string
 * comparison, logical operators—to {@link ProcedureDialect}.
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

    /** Whether the expression represents a string type (used to judge types for DISPLAY concatenation and comparisons). */
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
