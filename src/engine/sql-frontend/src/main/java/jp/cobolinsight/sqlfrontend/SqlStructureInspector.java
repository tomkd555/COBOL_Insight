package jp.cobolinsight.sqlfrontend;

import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Concat;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Modulo;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.ArrayList;
import java.util.List;

/**
 * JSqlParser の SELECT 構文木を走査し、SQL指摘 S001〜S003 の構造シグナルを算出する。
 * 述語の該当箇所は {@link MangledSql#restore} でホスト変数を原データ名へ復元して文字列で返す。
 */
final class SqlStructureInspector {

    private SqlStructureInspector() {
    }

    /** SELECT 句に * が出現するか(S001)。 */
    static boolean hasSelectStar(PlainSelect select) {
        List<SelectItem<?>> items = select.getSelectItems();
        if (items == null) {
            return false;
        }
        for (SelectItem<?> item : items) {
            if (item.getExpression() instanceof AllColumns) {
                return true;
            }
        }
        return false;
    }

    /** WHERE 左辺が列を関数・演算式で包む、または先頭 % の LIKE の該当箇所(S002)。 */
    static List<String> nonSargablePredicates(PlainSelect select, MangledSql mangled) {
        List<String> out = new ArrayList<>();
        collectNonSargable(select.getWhere(), mangled, out);
        return out;
    }

    /** WHERE/JOIN 条件の比較で片側が列引数の関数呼出しまたは CAST(col AS ..) の該当箇所(S003)。 */
    static List<String> functionOnColumnPredicates(PlainSelect select, MangledSql mangled) {
        List<String> out = new ArrayList<>();
        collectFunctionOnColumn(select.getWhere(), mangled, out);
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                if (join.getOnExpressions() != null) {
                    for (Expression on : join.getOnExpressions()) {
                        collectFunctionOnColumn(on, mangled, out);
                    }
                }
            }
        }
        return out;
    }

    private static void collectNonSargable(Expression e, MangledSql mangled, List<String> out) {
        if (e == null) {
            return;
        }
        if (e instanceof AndExpression a) {
            collectNonSargable(a.getLeftExpression(), mangled, out);
            collectNonSargable(a.getRightExpression(), mangled, out);
        } else if (e instanceof OrExpression o) {
            collectNonSargable(o.getLeftExpression(), mangled, out);
            collectNonSargable(o.getRightExpression(), mangled, out);
        } else if (e instanceof Parenthesis p) {
            collectNonSargable(p.getExpression(), mangled, out);
        } else if (e instanceof NotExpression n) {
            collectNonSargable(n.getExpression(), mangled, out);
        } else if (e instanceof LikeExpression like) {
            if (hasLeadingWildcard(like)) {
                out.add(describe(like, mangled));
            }
        } else if (e instanceof ComparisonOperator cmp) {
            if (wrapsColumnInFunctionOrArithmetic(cmp.getLeftExpression())) {
                out.add(describe(cmp, mangled));
            }
        }
    }

    private static void collectFunctionOnColumn(Expression e, MangledSql mangled, List<String> out) {
        if (e == null) {
            return;
        }
        if (e instanceof AndExpression a) {
            collectFunctionOnColumn(a.getLeftExpression(), mangled, out);
            collectFunctionOnColumn(a.getRightExpression(), mangled, out);
        } else if (e instanceof OrExpression o) {
            collectFunctionOnColumn(o.getLeftExpression(), mangled, out);
            collectFunctionOnColumn(o.getRightExpression(), mangled, out);
        } else if (e instanceof Parenthesis p) {
            collectFunctionOnColumn(p.getExpression(), mangled, out);
        } else if (e instanceof NotExpression n) {
            collectFunctionOnColumn(n.getExpression(), mangled, out);
        } else if (e instanceof ComparisonOperator cmp) {
            if (isFunctionOrCastOnColumn(cmp.getLeftExpression())
                    || isFunctionOrCastOnColumn(cmp.getRightExpression())) {
                out.add(describe(cmp, mangled));
            }
        }
    }

    private static boolean hasLeadingWildcard(LikeExpression like) {
        return like.getRightExpression() instanceof StringValue s && s.getValue().startsWith("%");
    }

    private static boolean wrapsColumnInFunctionOrArithmetic(Expression side) {
        if (side instanceof Function f) {
            return containsColumn(f.getParameters());
        }
        if (isArithmetic(side)) {
            return containsColumn(side);
        }
        return false;
    }

    private static boolean isFunctionOrCastOnColumn(Expression side) {
        if (side instanceof Function f) {
            return containsColumn(f.getParameters());
        }
        if (side instanceof CastExpression c) {
            return containsColumn(c.getLeftExpression());
        }
        return false;
    }

    private static boolean isArithmetic(Expression e) {
        return e instanceof Addition || e instanceof Subtraction
                || e instanceof Multiplication || e instanceof Division
                || e instanceof Modulo || e instanceof Concat;
    }

    private static boolean containsColumn(Expression e) {
        if (e == null) {
            return false;
        }
        if (e instanceof Column) {
            return true;
        }
        if (e instanceof Function f) {
            return containsColumn(f.getParameters());
        }
        if (e instanceof CastExpression c) {
            return containsColumn(c.getLeftExpression());
        }
        if (e instanceof Parenthesis p) {
            return containsColumn(p.getExpression());
        }
        if (e instanceof ExpressionList<?> list) {
            for (Expression item : list) {
                if (containsColumn(item)) {
                    return true;
                }
            }
            return false;
        }
        if (e instanceof BinaryExpression b) {
            return containsColumn(b.getLeftExpression()) || containsColumn(b.getRightExpression());
        }
        return false;
    }

    private static String describe(Expression e, MangledSql mangled) {
        return mangled.restore(e.toString()).trim();
    }
}
