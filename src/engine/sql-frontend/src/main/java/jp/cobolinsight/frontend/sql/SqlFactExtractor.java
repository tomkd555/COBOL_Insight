package jp.cobolinsight.frontend.sql;

import jp.cobolinsight.core.sql.SqlColumnRef;
import jp.cobolinsight.core.sql.SqlDeclaredColumn;
import jp.cobolinsight.core.sql.SqlSetPair;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.AlterTableColumnDefinitionContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.AlterTableNameContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.AlterTableStatementContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.AssignmentClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.ColumnNameContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.CommonTableExpressionContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.CorrelationClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.CorrelationNameContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.CreateTableColumnDefinitionContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.CursorNameContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.DataTypeContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.DeclareTableStatementContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.DeleteOperationContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.ExpressionContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.FetchTargetVariableContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.FromClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.FullSelectContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.FunctionInvocationContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.GroupByClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.HavingClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.HoldabilityContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.IncludeStatementContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.InsertOperationContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.InsertStatementContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.InsertStatementListOfValuesContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.IntoClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.JoinConditionContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.LabeledDurationContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.LockTableStatementContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.MatchingConditionContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.MergeStatementContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.MultipleRowFetchForClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.MultipleRowFetchIntoClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.MultipleRowInsertContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.NotNullPhraseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.OrderByClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.PositionedDeleteContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.PositionedUpdateContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.ProcedureNameContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.SearchConditionContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.SearchedDeleteContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.SearchedUpdateContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.SelectClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.SelectColumnsContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.SelectIntoStatementContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.SingleTableReferenceContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.SqlStatementContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.StatementNameContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.SubSelectContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.TableNameContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.TableReferenceContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.TruncateStatementContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.UpdateClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.UpdateOperationContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.ValuesIntoTargetVariableContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.ValuesList1Context;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.ValuesList2Context;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.WhereClauseContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.WhereCurrentOfPhraseContext;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static jp.cobolinsight.frontend.sql.SqlStructureInspector.descendants;
import static jp.cobolinsight.frontend.sql.SqlStructureInspector.sourceText;

/**
 * Reads the kind and the extracted facts of one statement off the Db2z parse tree, so a rule can
 * ask the model what the statement does instead of parsing its text again. Every name and every
 * excerpt is handed back with the host variables and the Japanese identifiers restored to their
 * original spelling by {@link MangledSql#restore}.
 *
 * <p>The kind comes from the name of the grammar rule the statement matched, not from a chain of
 * accessor calls: {@code sqlStatement} lists ninety alternatives, and naming each one by hand
 * would be ninety lines that say what the rule name already says.</p>
 *
 * <p>A fact belongs to the statement itself, never to a block nested inside it. A subquery's
 * WHERE is not the statement's WHERE, a common table expression's select list is not the
 * statement's select list, and a correlation name is read in the block that defines it.</p>
 */
final class SqlFactExtractor {

    /** The grammar rule of a statement alternative, to the kind that rule stands for. */
    private static final Map<String, SqlStatementKind> KIND_BY_RULE = kindByRule();

    /** The statement rules that are DDL without their name starting with create or alter. */
    private static final Set<String> DDL_RULES = Set.of("dropStatement", "grantStatement",
            "revokeStatement", "commentStatement", "labelStatement", "renameStatement",
            "declareGlobalTemporaryTableStatement");

    /** The kinds whose SQL text is built at run time, so no table can be named. */
    private static final Set<SqlStatementKind> DYNAMIC = Set.of(SqlStatementKind.PREPARE,
            SqlStatementKind.EXECUTE, SqlStatementKind.EXECUTE_IMMEDIATE);

    /** The kinds that name a table without accessing a row of it, or without being understood. */
    private static final Set<SqlStatementKind> NO_ACCESS = Set.of(SqlStatementKind.DECLARE_TABLE,
            SqlStatementKind.DDL, SqlStatementKind.OTHER);

    /** The kinds whose own query block is the statement's, so its clauses are the statement's. */
    static final Set<SqlStatementKind> QUERY_SHAPED = Set.of(SqlStatementKind.SELECT,
            SqlStatementKind.SELECT_INTO, SqlStatementKind.DECLARE_CURSOR,
            // An INSERT ... SELECT owns the query it reads its rows from.
            SqlStatementKind.INSERT);

    /** What a dynamic statement books instead of a table it cannot name. */
    private static final String UNKNOWN_TABLE = "?";

    /** The order the access letters are written in. */
    private static final String LETTERS = "RCUD";

    /**
     * The words a built-in function takes as a keyword argument: the date and time formats, the
     * units of a labelled duration and of TIMESTAMPDIFF, and the ends TRIM works from. The grammar
     * has a rule of its own for each of these only where the function has one — TRIM, EXTRACT —
     * and reads the rest as a name inside an ordinary argument list.
     */
    private static final Set<String> KEYWORD_ARGUMENTS = Set.of(
            "ISO", "USA", "EUR", "JIS", "LOCAL",
            "YEAR", "YEARS", "MONTH", "MONTHS", "DAY", "DAYS", "HOUR", "HOURS",
            "MINUTE", "MINUTES", "SECOND", "SECONDS", "MICROSECOND", "MICROSECONDS",
            "LEADING", "TRAILING", "BOTH");

    /**
     * A WITH UR, CS, RS or RR clause. The lookahead is what keeps a common table expression named
     * {@code UR} out: a CTE name is followed by AS or by its column list.
     */
    private static final Pattern ISOLATION =
            Pattern.compile("(?i)\\bWITH\\s+(UR|CS|RS|RR)\\b(?!\\s*(?:AS\\b|\\())");

    /** {@code FOR n ROWS} of a multi-row INSERT, which the grammar reads as one unsplit clause. */
    private static final Pattern INSERT_ROWSET =
            Pattern.compile("(?i)\\bFOR\\s+(?::(HV\\d+)|(\\d{1,9}))\\s+ROWS?\\b");

    /** An OPTIMIZE standing right in front of such a clause, which makes it another clause. */
    private static final Pattern OPTIMIZE_BEFORE = Pattern.compile("(?i)\\bOPTIMIZE\\s+$");

    private static final Pattern HOST_TOKEN = Pattern.compile(":(HV\\d+)");

    private SqlFactExtractor() {
    }

    /** The kind of the statement, read from the grammar rule its alternative matched. */
    static SqlStatementKind kindOf(SqlStatementContext statement) {
        ParserRuleContext alternative = alternativeOf(statement);
        if (alternative == null) {
            return SqlStatementKind.OTHER;
        }
        String rule = ruleNameOf(alternative);
        if (rule.equals("query")) {
            ParserRuleContext query = alternativeOf(alternative);
            return query != null && ruleNameOf(query).equals("selectIntoStatement")
                    ? SqlStatementKind.SELECT_INTO : SqlStatementKind.SELECT;
        }
        if (rule.startsWith("create") || rule.startsWith("alter") || DDL_RULES.contains(rule)) {
            return SqlStatementKind.DDL;
        }
        return KIND_BY_RULE.getOrDefault(rule, SqlStatementKind.OTHER);
    }

    /** Fills every fact the parse tree can supply. */
    static void extract(SqlStatementModel.Builder facts, SqlStatementContext statement,
            MangledSql mangled, SqlStatementKind kind) {
        ParserRuleContext block = ownQueryBlock(statement, kind);
        FullSelectContext fullSelect = ownFullSelect(statement, kind);
        List<TableNameContext> occurrences = tableOccurrences(statement);
        List<String> names = restoreAll(occurrences, mangled);
        List<UpdateClauseContext> updates = descendants(statement, UpdateClauseContext.class);
        Map<ParserRuleContext, Map<String, String>> correlations = new HashMap<>();
        List<String> cteNames = cteNames(statement, mangled);
        String cursorTable = updates.isEmpty() || kind != SqlStatementKind.DECLARE_CURSOR ? null
                : cursorUpdateTable(updates, block, mangled);
        Map<String, String> access = tableAccess(kind, names,
                names.indexOf(targetTable(statement, mangled, kind)), cursorTable,
                targetLetters(statement, kind));
        facts.referencedTables(access.isEmpty() ? List.of() : distinct(names))
                .tableAccess(access)
                .cursorName(cursorName(statement, mangled))
                .positionedCursor(positionedCursor(statement, mangled))
                .intoTargets(intoTargets(statement, mangled))
                .cteNames(cteNames)
                .columnRefs(columnRefs(statement, mangled, correlations, cteNames))
                .selectList(selectList(block, mangled))
                .setPairs(setPairs(statement, mangled))
                .declaredTable(declaredTable(statement, mangled))
                .declaredColumns(declaredColumns(statement, mangled))
                .includeMember(includeMember(statement, mangled))
                .procedureName(kind == SqlStatementKind.CALL ? nameOf(statement, mangled,
                        ProcedureNameContext.class) : null)
                .statementName(nameOf(statement, mangled, StatementNameContext.class))
                .withHold(withHold(statement))
                .forUpdate(!updates.isEmpty(), forUpdateColumns(updates, mangled))
                .hasWhere(hasWhere(statement, kind, block, fullSelect))
                .hasOrderBy(hasOrderBy(block, fullSelect))
                .dynamic(DYNAMIC.contains(kind))
                .isolation(isolationOf(sourceText(statement)));
        if (kind == SqlStatementKind.INSERT) {
            insert(facts, statement.insertStatement(), mangled);
        }
        rowset(facts, statement, mangled, kind);
    }

    /**
     * Which letters of R, C, U and D each table is accessed with, merged per table and written in
     * that order. Shared by the two analysis paths, because a degraded statement knows its kind
     * and its tables just as a parsed one does.
     *
     * @param occurrences the tables in source order, one entry per place the statement names one
     * @param targetIndex the occurrence the statement writes, or -1 when it writes none
     * @param cursorTable the table a FOR UPDATE cursor updates; null when there is none
     * @param targetLetters what the statement's own text says it does to its target, where the
     *                      kind alone does not say — the branches of a MERGE, the mode of a LOCK
     *                      TABLE; null leaves the decision to the kind
     */
    static Map<String, String> tableAccess(SqlStatementKind kind, List<String> occurrences,
            int targetIndex, String cursorTable, String targetLetters) {
        if (DYNAMIC.contains(kind)) {
            return Map.of(UNKNOWN_TABLE, UNKNOWN_TABLE);
        }
        if (NO_ACCESS.contains(kind)) {
            // A declaration names a table without reading or writing a row of it, and a statement
            // the tool only recognises as OTHER is not understood well enough to claim an access.
            return Map.of();
        }
        String written = targetLetters != null ? targetLetters : switch (kind) {
            case INSERT -> "C";
            case UPDATE -> "U";
            case DELETE -> "D";
            // A MERGE whose branches neither path could read still writes the row it matched.
            case MERGE -> "CU";
            // An EXCLUSIVE lock is taken to write the table; a SHARE lock reads it.
            case LOCK_TABLE -> "U";
            case TRUNCATE -> "D";
            default -> null;
        };
        Map<String, Set<Character>> letters = new LinkedHashMap<>();
        for (int i = 0; i < occurrences.size(); i++) {
            String name = occurrences.get(i);
            add(letters, name, i == targetIndex && written != null ? written : "R");
            if (name.equals(cursorTable)) {
                add(letters, name, "U");
            }
        }
        Map<String, String> access = new LinkedHashMap<>();
        letters.forEach((name, set) -> access.put(name, order(set)));
        return access;
    }

    /**
     * What the parse tree says the statement does to the table it writes, where its kind alone
     * leaves it open: the branches a MERGE carries, and the mode a LOCK TABLE takes.
     */
    private static String targetLetters(SqlStatementContext statement, SqlStatementKind kind) {
        return switch (kind) {
            case MERGE -> mergeLetters(
                    !descendants(statement, InsertOperationContext.class).isEmpty(),
                    !descendants(statement, UpdateOperationContext.class).isEmpty(),
                    !descendants(statement, DeleteOperationContext.class).isEmpty());
            case LOCK_TABLE -> statement.lockTableStatement().SHARE() == null ? null : "R";
            default -> null;
        };
    }

    /**
     * The letters the branches of a MERGE book on its target: a WHEN NOT MATCHED THEN INSERT
     * creates rows, a THEN UPDATE updates them and a THEN DELETE deletes them. Null for a merge
     * whose branches the caller could not read, which leaves the letters to the kind.
     */
    static String mergeLetters(boolean inserts, boolean updates, boolean deletes) {
        String letters = (inserts ? "C" : "") + (updates ? "U" : "") + (deletes ? "D" : "");
        return letters.isEmpty() ? null : letters;
    }

    private static void add(Map<String, Set<Character>> letters, String name, String added) {
        Set<Character> set = letters.computeIfAbsent(name, key -> new TreeSet<>());
        for (int i = 0; i < added.length(); i++) {
            set.add(added.charAt(i));
        }
    }

    private static String order(Set<Character> set) {
        StringBuilder out = new StringBuilder(set.size());
        for (int i = 0; i < LETTERS.length(); i++) {
            if (set.contains(LETTERS.charAt(i))) {
                out.append(LETTERS.charAt(i));
            }
        }
        return out.toString();
    }

    // ---- one fact at a time ----

    /**
     * Every place the statement names a table, in source order. A {@code tableName} that qualifies
     * a column (as in {@code Z.SOKO_CD}) or a star ({@code J.*}) names a correlation, and so does a
     * name a FROM clause or a common table expression of this statement defines: none of them is
     * a table.
     */
    private static List<TableNameContext> tableOccurrences(SqlStatementContext statement) {
        Set<String> notTables = new LinkedHashSet<>();
        for (CommonTableExpressionContext cte
                : descendants(statement, CommonTableExpressionContext.class)) {
            TableNameContext name = child(cte, TableNameContext.class);
            if (name != null) {
                notTables.add(name.getText());
            }
        }
        Set<String> correlations = new LinkedHashSet<>();
        for (CorrelationNameContext correlation
                : descendants(statement, CorrelationNameContext.class)) {
            correlations.add(correlation.getText());
        }
        List<TableNameContext> occurrences = new ArrayList<>();
        for (TableNameContext table : descendants(statement, TableNameContext.class)) {
            if (table.getParent() instanceof ColumnNameContext
                    || table.getParent() instanceof SelectColumnsContext
                    || notTables.contains(table.getText())) {
                continue;
            }
            // A correlation name is excluded by its spelling, which a real table may share:
            // FROM STOCK STOCK names the table once and the correlation once. Only an occurrence
            // outside a table-reference position can be the correlation.
            if (correlations.contains(table.getText()) && !isTableReference(table)) {
                continue;
            }
            occurrences.add(table);
        }
        return occurrences;
    }

    /** Whether the occurrence stands where the grammar puts a table and nothing else. */
    private static boolean isTableReference(TableNameContext table) {
        ParserRuleContext parent = table.getParent();
        return parent instanceof SingleTableReferenceContext
                || parent instanceof TableReferenceContext
                || parent instanceof FromClauseContext
                || parent instanceof InsertStatementContext
                || parent instanceof SearchedUpdateContext
                || parent instanceof PositionedUpdateContext
                || parent instanceof SearchedDeleteContext
                || parent instanceof PositionedDeleteContext
                || parent instanceof MergeStatementContext
                || parent instanceof LockTableStatementContext
                || parent instanceof TruncateStatementContext;
    }

    /** The table a DML statement writes: the one its own rule names, not one of a subquery. */
    private static String targetTable(SqlStatementContext statement, MangledSql mangled,
            SqlStatementKind kind) {
        ParserRuleContext owner = switch (kind) {
            case INSERT -> statement.insertStatement();
            case UPDATE -> alternativeOf(statement.updateStatement());
            case DELETE -> alternativeOf(statement.deleteStatement());
            case MERGE -> statement.mergeStatement();
            case LOCK_TABLE -> statement.lockTableStatement();
            case TRUNCATE -> statement.truncateStatement();
            default -> null;
        };
        TableNameContext table = owner == null ? null : child(owner, TableNameContext.class);
        return table == null ? null : mangled.restore(table.getText());
    }

    /**
     * The one table a FOR UPDATE cursor may update: the table a FOR UPDATE OF column is qualified
     * with, and otherwise the first table of the cursor query's own FROM clause.
     */
    private static String cursorUpdateTable(List<UpdateClauseContext> updates,
            ParserRuleContext block, MangledSql mangled) {
        // The FOR UPDATE clause hangs off the select statement, outside the block that defines the
        // correlations, so the qualifier is looked up in the cursor query's own block by hand.
        Map<String, String> byCorrelation =
                block == null ? Map.of() : blockCorrelations(block, mangled);
        for (UpdateClauseContext update : updates) {
            for (ColumnNameContext column : update.columnName()) {
                String qualifier = qualifierOf(column, mangled);
                if (qualifier != null) {
                    return byCorrelation.getOrDefault(qualifier, qualifier);
                }
            }
        }
        FromClauseContext from = block == null ? null : child(block, FromClauseContext.class);
        List<TableNameContext> tables = from == null ? List.of() : own(from, TableNameContext.class);
        return tables.isEmpty() ? null : mangled.restore(tables.get(0).getText());
    }

    private static String cursorName(SqlStatementContext statement, MangledSql mangled) {
        return nameOf(statement, mangled, CursorNameContext.class);
    }

    private static String positionedCursor(SqlStatementContext statement, MangledSql mangled) {
        List<WhereCurrentOfPhraseContext> positioned =
                descendants(statement, WhereCurrentOfPhraseContext.class);
        return positioned.isEmpty() ? null
                : mangled.restore(positioned.get(0).cursorName().getText());
    }

    /**
     * The original data names of the host variables the INTO list names, in source order. The four
     * clauses that carry one — SELECT INTO, single-row FETCH, multi-row FETCH and VALUES INTO —
     * are gathered and put back in the order the statement writes them. A target is the data name
     * alone; its indicator variable belongs to the binding, not to this list. An INTO DESCRIPTOR
     * names no target, so the descriptor area is not one.
     */
    private static List<String> intoTargets(SqlStatementContext statement, MangledSql mangled) {
        List<ParserRuleContext> clauses = new ArrayList<>();
        clauses.addAll(descendants(statement, IntoClauseContext.class));
        clauses.addAll(descendants(statement, FetchTargetVariableContext.class));
        for (MultipleRowFetchIntoClauseContext into
                : descendants(statement, MultipleRowFetchIntoClauseContext.class)) {
            if (into.DESCRIPTOR() == null) {
                clauses.add(into);
            }
        }
        clauses.addAll(descendants(statement, ValuesIntoTargetVariableContext.class));
        clauses.sort(Comparator.comparingInt(clause -> clause.getStart().getStartIndex()));
        List<String> names = new ArrayList<>();
        for (ParserRuleContext clause : clauses) {
            names.addAll(hostVariableNames(mangled, clause.getText()));
        }
        return names;
    }

    /**
     * Every column the statement refers to, taken from the clauses that hold one: the select list,
     * the WHERE, ON and HAVING predicates, ORDER BY, GROUP BY, the SET assignments and the INSERT
     * column list. A column definition of a DECLARE TABLE and the header of a common table
     * expression declare a name rather than refer to one, so neither is here.
     *
     * <p>A qualifier is resolved through the FROM clause of the block that encloses the reference,
     * walking outwards for a correlated reference; a qualifier no block defines stays as written.</p>
     */
    private static List<SqlColumnRef> columnRefs(SqlStatementContext statement, MangledSql mangled,
            Map<ParserRuleContext, Map<String, String>> correlations, List<String> cteNames) {
        Set<ColumnNameContext> columns = new LinkedHashSet<>();
        for (ParserRuleContext scope : columnScopes(statement)) {
            columns.addAll(descendants(scope, ColumnNameContext.class));
        }
        List<ColumnNameContext> ordered = new ArrayList<>(columns);
        ordered.sort(Comparator.comparingInt(column -> column.getStart().getStartIndex()));
        Set<String> ctes = new LinkedHashSet<>(cteNames);
        Map<ParserRuleContext, String> cteByBlock = new HashMap<>();
        List<SqlColumnRef> refs = new ArrayList<>();
        for (ColumnNameContext column : ordered) {
            String qualifier = qualifierOf(column, mangled);
            if (qualifier == null && isKeywordArgument(column)) {
                continue;
            }
            String table = qualifier != null
                    ? resolve(column, qualifier, mangled, correlations)
                    : ownCommonTableExpression(column, ctes, mangled, cteByBlock);
            refs.add(new SqlColumnRef(Optional.ofNullable(table),
                    mangled.restore(column.identifier1().getText())));
        }
        return refs;
    }

    /**
     * Whether an unqualified name is a built-in function's keyword argument rather than a column:
     * the date format of {@code CHAR(x, ISO)}, the unit of a labelled duration, the end TRIM works
     * from. Only inside a function call or a labelled duration, so a column really called MONTH is
     * still a column everywhere else, and a qualified {@code T.MONTH} is one even there.
     *
     * <p>ponytail: a word list, because the grammar reads the argument list of its generic scalar
     * function as plain expressions and gives these words no rule of their own. A column genuinely
     * named ISO or MONTH read inside a function is dropped with them; the upgrade is a rule per
     * function in the MAPA grammar, not a longer list here.</p>
     */
    private static boolean isKeywordArgument(ColumnNameContext column) {
        if (!KEYWORD_ARGUMENTS.contains(
                column.identifier1().getText().toUpperCase(Locale.ROOT))) {
            return false;
        }
        for (ParserRuleContext scope = column.getParent(); scope != null;
                scope = scope.getParent()) {
            if (scope instanceof FunctionInvocationContext
                    || scope instanceof LabeledDurationContext) {
                return true;
            }
        }
        return false;
    }

    /**
     * The common table expression an unqualified column belongs to: the one its own block reads,
     * when that block reads nothing else. A column a WITH clause defines is not a column of the
     * base table behind it, and a rule checking a reference against a DECLARE TABLE has to be able
     * to tell the two apart.
     */
    private static String ownCommonTableExpression(ColumnNameContext column, Set<String> ctes,
            MangledSql mangled, Map<ParserRuleContext, String> cteByBlock) {
        if (ctes.isEmpty()) {
            return null;
        }
        for (ParserRuleContext scope = column.getParent(); scope != null;
                scope = scope.getParent()) {
            if (definesCorrelations(scope)) {
                return cteByBlock.computeIfAbsent(scope,
                        block -> soleCommonTableExpressionOf(block, ctes, mangled));
            }
        }
        return null;
    }

    private static String soleCommonTableExpressionOf(ParserRuleContext block, Set<String> ctes,
            MangledSql mangled) {
        FromClauseContext from = child(block, FromClauseContext.class);
        List<TableNameContext> tables = from == null ? List.of() : own(from, TableNameContext.class);
        if (tables.size() != 1) {
            return null;
        }
        String name = mangled.restore(tables.get(0).getText());
        return ctes.contains(name) ? name : null;
    }

    /** The names the statement's WITH clause defines, in the order it defines them. */
    private static List<String> cteNames(SqlStatementContext statement, MangledSql mangled) {
        List<String> names = new ArrayList<>();
        for (CommonTableExpressionContext cte
                : descendants(statement, CommonTableExpressionContext.class)) {
            TableNameContext name = child(cte, TableNameContext.class);
            if (name != null) {
                names.add(mangled.restore(name.getText()));
            }
        }
        return names;
    }

    /** The clauses a column reference may stand in. */
    private static List<ParserRuleContext> columnScopes(SqlStatementContext statement) {
        List<ParserRuleContext> scopes = new ArrayList<>();
        scopes.addAll(descendants(statement, SelectClauseContext.class));
        scopes.addAll(descendants(statement, WhereClauseContext.class));
        scopes.addAll(descendants(statement, JoinConditionContext.class));
        scopes.addAll(descendants(statement, HavingClauseContext.class));
        scopes.addAll(descendants(statement, OrderByClauseContext.class));
        scopes.addAll(descendants(statement, GroupByClauseContext.class));
        scopes.addAll(descendants(statement, AssignmentClauseContext.class));
        // A searched UPDATE or DELETE and a MERGE hold their predicate without a clause rule
        // around it, so the search condition standing under the statement itself is taken.
        List<ParserRuleContext> predicateOwners = new ArrayList<>();
        predicateOwners.addAll(descendants(statement, SearchedUpdateContext.class));
        predicateOwners.addAll(descendants(statement, SearchedDeleteContext.class));
        predicateOwners.addAll(descendants(statement, MergeStatementContext.class));
        for (ParserRuleContext owner : predicateOwners) {
            scopes.addAll(children(owner, SearchConditionContext.class));
        }
        InsertStatementContext insert = statement.insertStatement();
        if (insert != null) {
            scopes.addAll(insert.columnName());
        }
        // A MERGE holds a predicate on each WHEN and a column list on its INSERT branch.
        for (MatchingConditionContext matching
                : descendants(statement, MatchingConditionContext.class)) {
            scopes.addAll(children(matching, SearchConditionContext.class));
        }
        for (InsertOperationContext branch
                : descendants(statement, InsertOperationContext.class)) {
            scopes.addAll(branch.columnName());
        }
        return scopes;
    }

    /** The qualifier a column is written with, restored; null when the column is unqualified. */
    private static String qualifierOf(ColumnNameContext column, MangledSql mangled) {
        if (column.correlationName() != null) {
            return mangled.restore(column.correlationName().getText());
        }
        return column.tableName() == null ? null : mangled.restore(column.tableName().getText());
    }

    /**
     * The table a qualifier stands for, looked up in the block that encloses the reference and
     * then in the blocks around it, so a correlation name shadowed by a nested block resolves to
     * the table that block defines.
     */
    private static String resolve(ColumnNameContext column, String qualifier, MangledSql mangled,
            Map<ParserRuleContext, Map<String, String>> correlations) {
        for (ParserRuleContext scope = column.getParent(); scope != null;
                scope = scope.getParent()) {
            if (!definesCorrelations(scope)) {
                continue;
            }
            String table = correlations
                    .computeIfAbsent(scope, block -> blockCorrelations(block, mangled))
                    .get(qualifier);
            if (table != null) {
                return table;
            }
        }
        return qualifier;
    }

    private static boolean definesCorrelations(ParserRuleContext scope) {
        return scope instanceof SubSelectContext || scope instanceof SelectIntoStatementContext
                || scope instanceof SearchedUpdateContext || scope instanceof PositionedUpdateContext
                || scope instanceof SearchedDeleteContext || scope instanceof PositionedDeleteContext
                || scope instanceof MergeStatementContext;
    }

    /** The correlation names one block defines itself, each to the table it stands for. */
    private static Map<String, String> blockCorrelations(ParserRuleContext block,
            MangledSql mangled) {
        Map<String, String> byCorrelation = new LinkedHashMap<>();
        List<ParserRuleContext> owners = new ArrayList<>(own(block, SingleTableReferenceContext.class));
        if (!(block instanceof SubSelectContext) && !(block instanceof SelectIntoStatementContext)) {
            // A searched UPDATE, DELETE or MERGE names its table and its correlation directly.
            owners.add(block);
        }
        for (ParserRuleContext owner : owners) {
            TableNameContext table = child(owner, TableNameContext.class);
            if (table == null) {
                continue;
            }
            CorrelationNameContext correlation = child(owner, CorrelationNameContext.class);
            if (correlation == null) {
                CorrelationClauseContext clause = child(owner, CorrelationClauseContext.class);
                correlation = clause == null ? null : clause.correlationName();
            }
            if (correlation != null) {
                byCorrelation.put(mangled.restore(correlation.getText()),
                        mangled.restore(table.getText()));
            }
        }
        return byCorrelation;
    }

    /**
     * The items of the statement's own select list. A set operation (UNION, EXCEPT, INTERSECT)
     * keeps the first branch, and the select list of a common table expression or of a subquery is
     * not the statement's own.
     */
    private static List<String> selectList(ParserRuleContext block, MangledSql mangled) {
        SelectClauseContext select = block == null ? null : child(block, SelectClauseContext.class);
        if (select == null) {
            return List.of();
        }
        if (select.SPLAT() != null) {
            return List.of("*");
        }
        List<String> items = new ArrayList<>();
        for (SelectColumnsContext item : select.selectColumns()) {
            items.add(excerpt(item, mangled));
        }
        return items;
    }

    /**
     * Whether the statement narrows its own rows. The WHERE of a subquery belongs to that
     * subquery, so it does not count; a positioned UPDATE or DELETE narrows through its cursor;
     * and a set operation narrows when any of its own branches does, because a UNION whose second
     * branch alone carries a WHERE still reads fewer rows than the whole table.
     */
    private static boolean hasWhere(SqlStatementContext statement, SqlStatementKind kind,
            ParserRuleContext block, FullSelectContext fullSelect) {
        return switch (kind) {
            case UPDATE -> ownWhere(alternativeOf(statement.updateStatement()));
            case DELETE -> ownWhere(alternativeOf(statement.deleteStatement()));
            case SELECT, SELECT_INTO, DECLARE_CURSOR, INSERT ->
                    block != null && child(block, WhereClauseContext.class) != null
                            || anyBranchNarrows(fullSelect);
            default -> false;
        };
    }

    /** Whether any top-level branch of a set operation carries a WHERE of its own. */
    private static boolean anyBranchNarrows(FullSelectContext fullSelect) {
        if (fullSelect == null) {
            return false;
        }
        for (SubSelectContext branch : fullSelect.subSelect()) {
            if (child(branch, WhereClauseContext.class) != null) {
                return true;
            }
        }
        for (FullSelectContext nested : fullSelect.fullSelect()) {
            if (anyBranchNarrows(nested)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the statement orders its own rows. A cursor's ORDER BY hangs off the full select
     * rather than the query block, and the ORDER BY of a subquery is not the statement's.
     */
    private static boolean hasOrderBy(ParserRuleContext block, FullSelectContext fullSelect) {
        return fullSelect != null && fullSelect.orderByClause() != null
                || block != null && child(block, OrderByClauseContext.class) != null;
    }

    /** The WHERE of a searched UPDATE or DELETE, or the cursor of a positioned one. */
    private static boolean ownWhere(ParserRuleContext owner) {
        if (owner instanceof SearchedUpdateContext update) {
            return update.WHERE() != null;
        }
        if (owner instanceof SearchedDeleteContext delete) {
            return delete.WHERE() != null;
        }
        return owner != null && child(owner, WhereCurrentOfPhraseContext.class) != null;
    }

    /** The assignments of an UPDATE, of a positioned UPDATE and of a MERGE UPDATE branch. */
    private static List<SqlSetPair> setPairs(SqlStatementContext statement, MangledSql mangled) {
        List<SqlSetPair> pairs = new ArrayList<>();
        for (AssignmentClauseContext clause
                : descendants(statement, AssignmentClauseContext.class)) {
            List<ColumnNameContext> columns = children(clause, ColumnNameContext.class);
            List<ValuesList1Context> values = children(clause, ValuesList1Context.class);
            for (int i = 0; i < Math.min(columns.size(), values.size()); i++) {
                pairs.add(new SqlSetPair(mangled.restore(columns.get(i).getText()),
                        excerpt(values.get(i), mangled)));
            }
        }
        return pairs;
    }

    /**
     * The column list and the VALUES items of an INSERT, one value per column. An INSERT ... SELECT
     * has no values, and an INSERT of several rows records the first row only, so the two lists
     * keep lining up.
     */
    private static void insert(SqlStatementModel.Builder facts, InsertStatementContext insert,
            MangledSql mangled) {
        if (insert == null) {
            return;
        }
        List<String> columns = new ArrayList<>();
        for (ColumnNameContext column : insert.columnName()) {
            columns.add(mangled.restore(column.getText()));
        }
        facts.insertColumns(columns).insertValues(firstRow(insert, mangled));
    }

    /** The first VALUES row of an INSERT, split into the value of each column. */
    private static List<String> firstRow(InsertStatementContext insert, MangledSql mangled) {
        MultipleRowInsertContext wide = insert.multipleRowInsert();
        if (wide != null) {
            return rowsOf(children(wide, ValuesList2Context.class), mangled);
        }
        List<InsertStatementListOfValuesContext> rows =
                descendants(insert, InsertStatementListOfValuesContext.class);
        if (!rows.isEmpty()) {
            return rowsOf(children(rows.get(0), ValuesList1Context.class), mangled);
        }
        List<ValuesList1Context> flat = descendants(insert, ValuesList1Context.class);
        return flat.isEmpty() ? List.of() : row(flat.get(0), mangled);
    }

    private static List<String> rowsOf(List<? extends ParserRuleContext> nodes,
            MangledSql mangled) {
        List<String> values = new ArrayList<>(nodes.size());
        for (ParserRuleContext node : nodes) {
            values.addAll(row(node, mangled));
        }
        return values;
    }

    /**
     * One VALUES row split into the value of each column. The grammar reads {@code (A, B, C)} as
     * one parenthesised expression rather than as three values, so the parentheses are opened here
     * and the row comes out one item per column, lining up with the column list.
     */
    private static List<String> row(ParserRuleContext value, MangledSql mangled) {
        if (value.getChildCount() != 1
                || !(value.getChild(0) instanceof ExpressionContext outer)
                || !isParenthesisedList(outer)) {
            return List.of(excerpt(value, mangled));
        }
        List<String> items = new ArrayList<>();
        for (ExpressionContext item : children(outer, ExpressionContext.class)) {
            items.add(excerpt(item, mangled));
        }
        return items;
    }

    /**
     * Whether an expression is nothing but a parenthesised list of expressions. {@code ((A+B)*C)}
     * also starts with a parenthesis, and splitting that one would tear an expression in two.
     */
    private static boolean isParenthesisedList(ExpressionContext expression) {
        int last = expression.getChildCount() - 1;
        if (last < 2 || !isToken(expression.getChild(0), DB2zSQLParser.LPAREN)
                || !isToken(expression.getChild(last), DB2zSQLParser.RPAREN)) {
            return false;
        }
        for (int i = 1; i < last; i++) {
            ParseTree child = expression.getChild(i);
            if (!(child instanceof ExpressionContext) && !isToken(child, DB2zSQLParser.COMMA)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isToken(ParseTree node, int type) {
        return node instanceof TerminalNode terminal && terminal.getSymbol().getType() == type;
    }

    /**
     * The table a DECLARE TABLE, a CREATE TABLE or an ALTER TABLE that adds a column declares, as
     * the statement writes it.
     */
    private static String declaredTable(SqlStatementContext statement, MangledSql mangled) {
        DeclareTableStatementContext declare = statement.declareTableStatement();
        ParserRuleContext owner = declare != null ? declare : statement.createTableStatement();
        if (owner == null) {
            return alteredTable(statement, mangled);
        }
        TableNameContext table = child(owner, TableNameContext.class);
        return table == null ? null : mangled.restore(table.getText());
    }

    /**
     * The table an {@code ALTER TABLE … ADD COLUMN} adds its column to. A column added there
     * belongs to the table as much as one the CREATE TABLE wrote, and a rule checking a reference
     * against the declared columns has to see it. An ALTER that adds no column declares nothing.
     * The name is a rule of its own in the grammar, so it is not the tableName the others carry.
     */
    private static String alteredTable(SqlStatementContext statement, MangledSql mangled) {
        AlterTableStatementContext alter = statement.alterTableStatement();
        if (alter == null
                || descendants(alter, AlterTableColumnDefinitionContext.class).isEmpty()) {
            return null;
        }
        AlterTableNameContext name = child(alter, AlterTableNameContext.class);
        return name == null ? null : mangled.restore(name.getText());
    }

    /**
     * The columns of a DECLARE TABLE, a CREATE TABLE or an {@code ALTER TABLE … ADD COLUMN}, each
     * with its type and nullability.
     */
    private static List<SqlDeclaredColumn> declaredColumns(SqlStatementContext statement,
            MangledSql mangled) {
        List<SqlDeclaredColumn> columns = new ArrayList<>();
        DeclareTableStatementContext declare = statement.declareTableStatement();
        if (declare != null) {
            // The three parts of one column stand side by side under the statement, so the
            // children are walked in order rather than indexed: NOT NULL is optional per column.
            String name = null;
            String type = "";
            boolean nullable = true;
            for (int i = 0; i < declare.getChildCount(); i++) {
                ParseTree part = declare.getChild(i);
                if (part instanceof ColumnNameContext column) {
                    if (name != null) {
                        columns.add(new SqlDeclaredColumn(name, type, nullable));
                    }
                    name = mangled.restore(column.getText());
                    type = "";
                    nullable = true;
                } else if (name == null) {
                    continue;
                } else if (part instanceof DataTypeContext dataType) {
                    type = excerpt(dataType, mangled);
                } else if (part instanceof NotNullPhraseContext) {
                    nullable = false;
                }
            }
            if (name != null) {
                columns.add(new SqlDeclaredColumn(name, type, nullable));
            }
        }
        for (CreateTableColumnDefinitionContext definition
                : descendants(statement, CreateTableColumnDefinitionContext.class)) {
            DataTypeContext dataType = definition.dataType();
            columns.add(new SqlDeclaredColumn(mangled.restore(definition.columnName().getText()),
                    dataType == null ? "" : excerpt(dataType, mangled),
                    // getText drops the layout, so NOT NULL reads as one word here.
                    !definition.getText().toUpperCase(Locale.ROOT).contains("NOTNULL")));
        }
        for (AlterTableColumnDefinitionContext definition
                : descendants(statement, AlterTableColumnDefinitionContext.class)) {
            // The added column names its type without going through the dataType rule.
            ParserRuleContext type = definition.builtInType() != null
                    ? definition.builtInType() : definition.distinctTypeName();
            columns.add(new SqlDeclaredColumn(mangled.restore(definition.columnName().getText()),
                    type == null ? "" : excerpt(type, mangled),
                    !definition.getText().toUpperCase(Locale.ROOT).contains("NOTNULL")));
        }
        return columns;
    }

    private static String includeMember(SqlStatementContext statement, MangledSql mangled) {
        IncludeStatementContext include = statement.includeStatement();
        return include == null ? null : mangled.restore(include.memberName().getText());
    }

    /** WITH HOLD on a cursor declaration. WITHOUT HOLD matches the same rule and does not count. */
    private static boolean withHold(SqlStatementContext statement) {
        for (HoldabilityContext holdability : descendants(statement, HoldabilityContext.class)) {
            if (holdability.WITH() != null && holdability.WITHOUT() == null) {
                return true;
            }
        }
        return false;
    }

    private static List<String> forUpdateColumns(List<UpdateClauseContext> updates,
            MangledSql mangled) {
        List<String> columns = new ArrayList<>();
        for (UpdateClauseContext update : updates) {
            for (ColumnNameContext column : update.columnName()) {
                columns.add(mangled.restore(column.getText()));
            }
        }
        return columns;
    }

    /**
     * The isolation level of a WITH UR, CS, RS or RR clause, read from the text rather than the
     * tree for the same reason {@code hasWithUr} is: right after "FROM table" the grammar also
     * fits "WITH UR" as a correlation name plus a correlation clause, and then no isolationClause
     * node exists to find.
     */
    static String isolationOf(String text) {
        Matcher matcher = ISOLATION.matcher(SqlTextScanner.maskStringLiterals(text));
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /** The row count of a multi-row FETCH or INSERT, as a literal or as the host variable's name. */
    private static void rowset(SqlStatementModel.Builder facts, SqlStatementContext statement,
            MangledSql mangled, SqlStatementKind kind) {
        List<MultipleRowFetchForClauseContext> clauses =
                descendants(statement, MultipleRowFetchForClauseContext.class);
        if (!clauses.isEmpty()) {
            MultipleRowFetchForClauseContext clause = clauses.get(0);
            if (clause.INTEGERLITERAL() != null) {
                facts.rowsetSize(rowCount(clause.INTEGERLITERAL().getText()));
            } else if (clause.hostVariable() != null) {
                List<String> names = hostVariableNames(mangled, clause.hostVariable().getText());
                facts.rowsetHostVariable(names.isEmpty() ? null : names.get(0));
            }
            return;
        }
        if (kind == SqlStatementKind.INSERT) {
            insertRowset(facts, sourceText(statement), mangled);
        }
    }

    /**
     * The {@code FOR n ROWS} of a multi-row INSERT, read from the text. The Db2 grammar takes that
     * clause only with an ATOMIC phrase behind it, so most of the field's multi-row inserts reach
     * this by the degraded path.
     */
    static void insertRowset(SqlStatementModel.Builder facts, String text, MangledSql mangled) {
        String scanned = SqlTextScanner.maskStringLiterals(text);
        Matcher matcher = INSERT_ROWSET.matcher(scanned);
        while (matcher.find()) {
            // OPTIMIZE FOR n ROWS counts rows the optimizer should plan for, not rows to write.
            if (OPTIMIZE_BEFORE.matcher(scanned.substring(0, matcher.start())).find()) {
                continue;
            }
            if (matcher.group(2) != null) {
                facts.rowsetSize(rowCount(matcher.group(2)));
            } else {
                List<String> names = hostVariableNames(mangled, ":" + matcher.group(1));
                facts.rowsetHostVariable(names.isEmpty() ? null : names.get(0));
            }
            return;
        }
    }

    private static Integer rowCount(String literal) {
        try {
            return Integer.valueOf(literal);
        } catch (NumberFormatException e) {
            // A row count no int can hold is no row count; the statement keeps its other facts.
            return null;
        }
    }

    // ---- shared ----

    /** The original data names of the host variables the text holds, in the order it writes them. */
    static List<String> hostVariableNames(MangledSql mangled, String text) {
        List<String> names = new ArrayList<>();
        Matcher matcher = HOST_TOKEN.matcher(text);
        while (matcher.find()) {
            HostVariableReference reference = mangled.reference(matcher.group(1));
            if (reference != null) {
                names.add(reference.dataName());
            }
        }
        return names;
    }

    /** The first name of the given rule the statement holds, restored; null when it holds none. */
    private static <T extends ParserRuleContext> String nameOf(SqlStatementContext statement,
            MangledSql mangled, Class<T> type) {
        List<T> names = descendants(statement, type);
        return names.isEmpty() ? null : mangled.restore(names.get(0).getText());
    }

    private static List<String> restoreAll(List<? extends ParserRuleContext> nodes,
            MangledSql mangled) {
        List<String> names = new ArrayList<>(nodes.size());
        for (ParserRuleContext node : nodes) {
            names.add(mangled.restore(node.getText()));
        }
        return names;
    }

    private static List<String> distinct(List<String> names) {
        return List.copyOf(new LinkedHashSet<>(names));
    }

    /** The source text of a node with the layout kept and the mangled names put back. */
    private static String excerpt(ParserRuleContext context, MangledSql mangled) {
        return mangled.restore(sourceText(context)).trim();
    }

    /** The alternative a rule matched: its first child that is a rule of its own. */
    private static ParserRuleContext alternativeOf(ParserRuleContext context) {
        if (context == null) {
            return null;
        }
        for (int i = 0; i < context.getChildCount(); i++) {
            if (context.getChild(i) instanceof ParserRuleContext child) {
                return child;
            }
        }
        return null;
    }

    private static String ruleNameOf(ParserRuleContext context) {
        return DB2zSQLParser.ruleNames[context.getRuleIndex()];
    }

    /**
     * The query block the statement is itself: the first one that is not a common table
     * expression's. Only a query-shaped statement has one — the first block an UPDATE or a DELETE
     * holds is the subquery of its SET or its WHERE, and that block is nobody's select list.
     */
    private static ParserRuleContext ownQueryBlock(SqlStatementContext statement,
            SqlStatementKind kind) {
        return QUERY_SHAPED.contains(kind) ? firstOwn(statement, true) : null;
    }

    /** The full select the statement is itself, which is where a cursor's ORDER BY hangs. */
    private static FullSelectContext ownFullSelect(SqlStatementContext statement,
            SqlStatementKind kind) {
        return QUERY_SHAPED.contains(kind)
                ? (FullSelectContext) firstOwn(statement, false) : null;
    }

    /** The first query block, or the first full select, that no common table expression owns. */
    private static ParserRuleContext firstOwn(ParseTree node, boolean wantBlock) {
        if (node instanceof CommonTableExpressionContext) {
            return null;
        }
        if (wantBlock
                ? node instanceof SubSelectContext || node instanceof SelectIntoStatementContext
                : node instanceof FullSelectContext) {
            return (ParserRuleContext) node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            ParserRuleContext found = firstOwn(node.getChild(i), wantBlock);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Every descendant of the given rule type that no nested query block owns. */
    private static <T extends ParserRuleContext> List<T> own(ParserRuleContext root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            collectOwn(root.getChild(i), type, found);
        }
        return found;
    }

    private static <T extends ParserRuleContext> void collectOwn(ParseTree node, Class<T> type,
            List<T> found) {
        if (node instanceof SubSelectContext || node instanceof SelectIntoStatementContext
                || node instanceof CommonTableExpressionContext) {
            return;
        }
        if (type.isInstance(node)) {
            found.add(type.cast(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectOwn(node.getChild(i), type, found);
        }
    }

    /** The first direct child of the given rule type, or null. */
    private static <T extends ParserRuleContext> T child(ParserRuleContext parent, Class<T> type) {
        List<T> found = children(parent, type);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Every direct child of the given rule type, in source order. */
    private static <T extends ParserRuleContext> List<T> children(ParserRuleContext parent,
            Class<T> type) {
        List<T> found = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            ParseTree child = parent.getChild(i);
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
        }
        return found;
    }

    private static Map<String, SqlStatementKind> kindByRule() {
        Map<String, SqlStatementKind> byRule = new LinkedHashMap<>();
        byRule.put("insertStatement", SqlStatementKind.INSERT);
        byRule.put("updateStatement", SqlStatementKind.UPDATE);
        byRule.put("deleteStatement", SqlStatementKind.DELETE);
        byRule.put("mergeStatement", SqlStatementKind.MERGE);
        byRule.put("declareCursorStatement", SqlStatementKind.DECLARE_CURSOR);
        byRule.put("openStatement", SqlStatementKind.OPEN);
        byRule.put("fetchStatement", SqlStatementKind.FETCH);
        byRule.put("closeStatement", SqlStatementKind.CLOSE);
        byRule.put("commitStatement", SqlStatementKind.COMMIT);
        byRule.put("rollbackStatement", SqlStatementKind.ROLLBACK);
        byRule.put("savepointStatement", SqlStatementKind.SAVEPOINT);
        byRule.put("releaseSavepointStatement", SqlStatementKind.RELEASE_SAVEPOINT);
        byRule.put("callStatement", SqlStatementKind.CALL);
        byRule.put("lockTableStatement", SqlStatementKind.LOCK_TABLE);
        byRule.put("setAssignmentStatement", SqlStatementKind.SET);
        byRule.put("setConnectionStatement", SqlStatementKind.SET);
        byRule.put("setEncryptionPasswordStatement", SqlStatementKind.SET);
        byRule.put("setPathStatement", SqlStatementKind.SET);
        byRule.put("setSchemaStatement", SqlStatementKind.SET);
        byRule.put("setSessionTimezoneStatement", SqlStatementKind.SET);
        byRule.put("setSpecialRegisterStatement", SqlStatementKind.SET);
        byRule.put("valuesIntoStatement", SqlStatementKind.VALUES_INTO);
        byRule.put("prepareStatement", SqlStatementKind.PREPARE);
        byRule.put("executeStatement", SqlStatementKind.EXECUTE);
        byRule.put("executeImmediateStatement", SqlStatementKind.EXECUTE_IMMEDIATE);
        byRule.put("describeStatement", SqlStatementKind.DESCRIBE);
        byRule.put("wheneverStatement", SqlStatementKind.WHENEVER);
        byRule.put("includeStatement", SqlStatementKind.INCLUDE);
        byRule.put("declareTableStatement", SqlStatementKind.DECLARE_TABLE);
        byRule.put("declareStatementStatement", SqlStatementKind.DECLARE_STATEMENT);
        byRule.put("declareVariableStatement", SqlStatementKind.DECLARE_VARIABLE);
        byRule.put("beginDeclareSectionStatement", SqlStatementKind.BEGIN_DECLARE_SECTION);
        byRule.put("endDeclareSectionStatement", SqlStatementKind.END_DECLARE_SECTION);
        byRule.put("getDiagnosticsStatement", SqlStatementKind.GET_DIAGNOSTICS);
        byRule.put("connectStatement", SqlStatementKind.CONNECT);
        byRule.put("truncateStatement", SqlStatementKind.TRUNCATE);
        byRule.put("allocateCursorStatement", SqlStatementKind.ALLOCATE_CURSOR);
        byRule.put("associateLocatorsStatement", SqlStatementKind.ASSOCIATE_LOCATORS);
        return Map.copyOf(byRule);
    }
}
