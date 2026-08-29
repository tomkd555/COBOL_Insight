package jp.cobolinsight.frontend.sql;

import jp.cobolinsight.core.sql.CursorSignals;
import jp.cobolinsight.core.sql.SqlStructureSignals;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.BasicPredicateContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.CastSpecificationContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.ColumnNameContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.DeclareCursorStatementContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.ExpressionContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.FetchClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.FunctionInvocationContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.JoinConditionContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.LikePredicateContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.OperatorContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.OptimizeClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.PredicateContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.ReadOnlyClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.SelectClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.SqlStatementContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.UpdateClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.WhereClauseContext;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the structure signals for the SQL advice rules (S001 to S006) off the Db2z parse tree.
 * Predicate excerpts are handed back with the host variables restored to their original data
 * names by {@link MangledSql#restore}.
 */
final class SqlStructureInspector {

    private static final Pattern WITH_UR =
            Pattern.compile("\\bWITH\\s+UR\\b", Pattern.CASE_INSENSITIVE);

    private SqlStructureInspector() {
    }

    /**
     * Builds the structure signals of one statement.
     *
     * @param statement         the parsed statement
     * @param mangled           the mangled SQL, used to restore data names in the excerpts
     * @param cursor            the cursor signals for DECLARE CURSOR, empty otherwise
     * @param inspectPredicates whether to walk WHERE and JOIN predicates (S001 to S003).
     *                          Only query-shaped statements carry those signals.
     * @return the structure signals
     */
    static SqlStructureSignals signals(SqlStatementContext statement, MangledSql mangled,
            Optional<CursorSignals> cursor, boolean inspectPredicates) {
        boolean selectStar = false;
        List<String> nonSargable = List.of();
        List<String> functionOnColumn = List.of();
        if (inspectPredicates) {
            selectStar = hasSelectStar(statement);
            nonSargable = nonSargablePredicates(statement, mangled);
            functionOnColumn = functionOnColumnPredicates(statement, mangled);
        }
        return new SqlStructureSignals(selectStar, nonSargable, functionOnColumn, cursor,
                hasFetchFirst(statement), hasOptimizeFor(statement), hasWithUr(statement));
    }

    /** Cursor name and the FOR READ ONLY / FOR FETCH ONLY / FOR UPDATE OF clauses (S004). */
    static CursorSignals cursorSignals(DeclareCursorStatementContext declare) {
        boolean forReadOnly = false;
        boolean forFetchOnly = false;
        for (ReadOnlyClauseContext readOnly : descendants(declare, ReadOnlyClauseContext.class)) {
            forReadOnly |= readOnly.READ() != null;
            forFetchOnly |= readOnly.FETCH() != null;
        }
        List<UpdateClauseContext> updates = descendants(declare, UpdateClauseContext.class);
        List<String> columns = new ArrayList<>();
        for (UpdateClauseContext update : updates) {
            for (ColumnNameContext column : update.columnName()) {
                columns.add(column.getText());
            }
        }
        return new CursorSignals(declare.cursorName().getText(), forReadOnly, forFetchOnly,
                !updates.isEmpty(), columns);
    }

    /** SELECT * in a select list (S001). A qualified {@code T.*} is not counted, as before. */
    private static boolean hasSelectStar(SqlStatementContext statement) {
        for (SelectClauseContext select : descendants(statement, SelectClauseContext.class)) {
            if (select.SPLAT() != null) {
                return true;
            }
        }
        return false;
    }

    /** FETCH FIRST n ROWS ONLY (S005). The LIMIT alternative of the same rule does not count. */
    private static boolean hasFetchFirst(SqlStatementContext statement) {
        for (FetchClauseContext fetch : descendants(statement, FetchClauseContext.class)) {
            if (fetch.FETCH() != null) {
                return true;
            }
        }
        return false;
    }

    /** OPTIMIZE FOR n ROWS (S006). */
    private static boolean hasOptimizeFor(SqlStatementContext statement) {
        return !descendants(statement, OptimizeClauseContext.class).isEmpty();
    }

    /**
     * WITH UR, reported as a hint only. Read from the text, not from the tree: right after
     * "FROM table" the grammar also fits "WITH UR" as a correlation name plus a correlation
     * clause, and then no isolationClause node exists to find.
     */
    private static boolean hasWithUr(SqlStatementContext statement) {
        return WITH_UR.matcher(SqlTextScanner.maskStringLiterals(sourceText(statement))).find();
    }

    /**
     * WHERE predicates whose left side wraps a column in a function or an arithmetic
     * expression, plus LIKE patterns starting with % (S002). JOIN conditions are out of
     * scope here; only the WHERE clause decides whether an index can filter rows.
     */
    private static List<String> nonSargablePredicates(SqlStatementContext statement,
            MangledSql mangled) {
        List<String> out = new ArrayList<>();
        for (WhereClauseContext where : descendants(statement, WhereClauseContext.class)) {
            for (PredicateContext predicate : descendants(where, PredicateContext.class)) {
                if (isNonSargable(predicate)) {
                    out.add(describe(predicate, mangled));
                }
            }
        }
        return out;
    }

    /**
     * WHERE and JOIN comparisons with a column wrapped in a function call or a CAST on
     * either side (S003).
     */
    private static List<String> functionOnColumnPredicates(SqlStatementContext statement,
            MangledSql mangled) {
        List<String> out = new ArrayList<>();
        List<ParserRuleContext> scopes = new ArrayList<>();
        scopes.addAll(descendants(statement, WhereClauseContext.class));
        scopes.addAll(descendants(statement, JoinConditionContext.class));
        for (ParserRuleContext scope : scopes) {
            for (PredicateContext predicate : descendants(scope, PredicateContext.class)) {
                BasicPredicateContext basic = predicate.basicPredicate();
                if (basic == null) {
                    continue;
                }
                List<ExpressionContext> sides = basic.expression();
                if (sides.stream().anyMatch(SqlStructureInspector::isFunctionOrCastOnColumn)) {
                    out.add(describe(predicate, mangled));
                }
            }
        }
        return out;
    }

    private static boolean isNonSargable(PredicateContext predicate) {
        LikePredicateContext like = predicate.likePredicate();
        if (like != null) {
            List<ExpressionContext> sides = like.expression();
            return sides.size() > 1 && sides.get(1).getText().startsWith("'%");
        }
        BasicPredicateContext basic = predicate.basicPredicate();
        if (basic == null || basic.expression().isEmpty()) {
            return false;
        }
        return wrapsColumnInFunctionOrArithmetic(basic.expression().get(0));
    }

    private static boolean wrapsColumnInFunctionOrArithmetic(ExpressionContext side) {
        FunctionInvocationContext function = child(side, FunctionInvocationContext.class);
        if (function != null) {
            return containsColumn(function);
        }
        // An arithmetic expression is an expression rule with an operator among its own children.
        return child(side, OperatorContext.class) != null && containsColumn(side);
    }

    private static boolean isFunctionOrCastOnColumn(ExpressionContext side) {
        FunctionInvocationContext function = child(side, FunctionInvocationContext.class);
        if (function != null && containsColumn(function)) {
            return true;
        }
        CastSpecificationContext cast = child(side, CastSpecificationContext.class);
        return cast != null && containsColumn(cast);
    }

    private static boolean containsColumn(ParserRuleContext context) {
        return !descendants(context, ColumnNameContext.class).isEmpty();
    }

    /** The source text of the excerpt, with the mangled host variables restored. */
    private static String describe(ParserRuleContext context, MangledSql mangled) {
        return mangled.restore(sourceText(context)).trim();
    }

    /** The source text the context spans, layout included (unlike {@code getText}). */
    private static String sourceText(ParserRuleContext context) {
        Interval span = new Interval(context.getStart().getStartIndex(),
                context.getStop().getStopIndex());
        return context.getStart().getInputStream().getText(span);
    }

    /** The first direct child of the given rule type, or null. */
    private static <T extends ParserRuleContext> T child(ParserRuleContext parent, Class<T> type) {
        if (parent == null) {
            return null;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            ParseTree child = parent.getChild(i);
            if (type.isInstance(child)) {
                return type.cast(child);
            }
        }
        return null;
    }

    /** Every descendant of the given rule type, in source order. */
    static <T extends ParserRuleContext> List<T> descendants(ParserRuleContext root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T extends ParserRuleContext> void collect(ParseTree node, Class<T> type,
            List<T> out) {
        if (node == null) {
            return;
        }
        if (type.isInstance(node)) {
            out.add(type.cast(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), type, out);
        }
    }
}
