package jp.cobolinsight.frontend.sql;

import jp.cobolinsight.core.sql.CursorSignals;
import jp.cobolinsight.core.sql.SqlStructureSignals;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLLexer;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.ColumnNameContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.QueryContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.SqlStatementContext;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.TableNameContext;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses one embedded SQL statement with the MAPA Db2 for z/OS grammar and reads the statement
 * kind, the referenced tables, the host variables (restored to their original data names) and
 * the Db2 clauses off the parse tree.
 *
 * <p>Host variables are mangled to {@code :HVn} first, because the grammar's identifier token
 * does not accept the Japanese data names this tool has to handle.</p>
 */
public final class SqlStatementAnalyzer {

    private static final Pattern TOKEN = Pattern.compile(":(HV\\d+)");

    private final HostVariableMangler mangler = new HostVariableMangler();

    public SqlAnalysisResult analyze(SqlBlock block) {
        MangleResult mangleResult = mangler.mangle(SqlTextScanner.maskComments(block.sqlText()));
        if (mangleResult instanceof MangleResult.NotAnalyzable notAnalyzable) {
            return notAnalyzable(notAnalyzable.reason(), null);
        }
        MangledSql mangled = ((MangleResult.Mangled) mangleResult).sql();
        // The grammar ignores layout, but collapsing it keeps the predicate excerpts on one line.
        String sql = mangled.sql().replaceAll("\\s+", " ").trim();
        if (sql.isEmpty()) {
            return notAnalyzable("SQL text is empty", mangled);
        }
        ErrorCollector errors = new ErrorCollector();
        DB2zSQLLexer lexer = new DB2zSQLLexer(CharStreams.fromString(sql));
        lexer.removeErrorListeners();
        lexer.addErrorListener(errors);
        DB2zSQLParser parser = new DB2zSQLParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(errors);
        List<SqlStatementContext> statements = parser.startRule().sqlStatement();
        if (errors.firstMessage != null) {
            return notAnalyzable("the Db2z grammar rejects the statement: " + errors.firstMessage,
                    mangled);
        }
        if (statements.isEmpty()) {
            return notAnalyzable("no SQL statement was recognised", mangled);
        }
        return dispatch(mangled, statements.get(0));
    }

    private static SqlAnalysisResult dispatch(MangledSql mangled, SqlStatementContext statement) {
        if (statement.declareCursorStatement() != null) {
            CursorSignals cursor =
                    SqlStructureInspector.cursorSignals(statement.declareCursorStatement());
            return analyzed(mangled, SqlStatementKind.DECLARE_CURSOR, cursor.cursorName(),
                    tables(statement), List.of(),
                    SqlStructureInspector.signals(statement, mangled, Optional.of(cursor), true));
        }
        if (statement.openStatement() != null) {
            return cursorStatement(mangled, SqlStatementKind.OPEN_CURSOR, statement,
                    statement.openStatement().cursorName().getText(), List.of());
        }
        if (statement.closeStatement() != null) {
            return cursorStatement(mangled, SqlStatementKind.CLOSE_CURSOR, statement,
                    statement.closeStatement().cursorName().getText(), List.of());
        }
        if (statement.fetchStatement() != null) {
            var fetch = statement.fetchStatement();
            ParserRuleContext into = fetch.singleRowFetch() != null
                    ? fetch.singleRowFetch() : fetch.multipleRowFetch();
            return cursorStatement(mangled, SqlStatementKind.FETCH, statement,
                    fetch.cursorName().getText(), hostVariableNames(mangled, into));
        }
        QueryContext query = statement.query();
        if (query != null) {
            if (query.selectIntoStatement() != null) {
                List<String> intoTargets = hostVariableNames(mangled,
                        query.selectIntoStatement().intoClause());
                return analyzed(mangled, SqlStatementKind.SELECT_INTO, null, tables(statement),
                        intoTargets,
                        SqlStructureInspector.signals(statement, mangled, Optional.empty(), true));
            }
            return analyzed(mangled, SqlStatementKind.SELECT, null, tables(statement), List.of(),
                    SqlStructureInspector.signals(statement, mangled, Optional.empty(), true));
        }
        if (statement.insertStatement() != null) {
            return dml(mangled, SqlStatementKind.INSERT, statement);
        }
        if (statement.updateStatement() != null) {
            return dml(mangled, SqlStatementKind.UPDATE, statement);
        }
        if (statement.deleteStatement() != null) {
            return dml(mangled, SqlStatementKind.DELETE, statement);
        }
        return analyzed(mangled, SqlStatementKind.OTHER, null, List.of(), List.of(),
                SqlStructureInspector.signals(statement, mangled, Optional.empty(), false));
    }

    private static SqlAnalysisResult dml(MangledSql mangled, SqlStatementKind kind,
            SqlStatementContext statement) {
        return analyzed(mangled, kind, null, tables(statement), List.of(),
                SqlStructureInspector.signals(statement, mangled, Optional.empty(), false));
    }

    private static SqlAnalysisResult cursorStatement(MangledSql mangled, SqlStatementKind kind,
            SqlStatementContext statement, String cursorName, List<String> intoTargets) {
        return analyzed(mangled, kind, cursorName, List.of(), intoTargets,
                SqlStructureInspector.signals(statement, mangled, Optional.empty(), false));
    }

    /**
     * The tables the statement reads or writes. A {@code tableName} that qualifies a column
     * (as in {@code Z.SOKO_CD}) names a correlation, not a table, so it is left out.
     */
    private static List<String> tables(SqlStatementContext statement) {
        Set<String> names = new LinkedHashSet<>();
        for (TableNameContext table
                : SqlStructureInspector.descendants(statement, TableNameContext.class)) {
            if (!(table.getParent() instanceof ColumnNameContext)) {
                names.add(table.getText());
            }
        }
        return List.copyOf(names);
    }

    /** The original data names of the host variables under the given clause, in source order. */
    private static List<String> hostVariableNames(MangledSql mangled, ParserRuleContext clause) {
        if (clause == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        Matcher m = TOKEN.matcher(clause.getText());
        while (m.find()) {
            String token = m.group(1);
            mangled.hostVariables().stream()
                    .filter(ref -> ref.token().equals(token))
                    .findFirst()
                    .ifPresent(ref -> names.add(ref.dataName()));
        }
        return List.copyOf(names);
    }

    private static SqlAnalysisResult analyzed(MangledSql mangled, SqlStatementKind kind,
            String cursorName, List<String> tables, List<String> intoTargets,
            SqlStructureSignals signals) {
        return new SqlAnalysisResult(AnalysisStatus.ANALYZED, null, kind, cursorName,
                tables, mangled.hostVariables(), intoTargets, mangled.sql(), signals);
    }

    private static SqlAnalysisResult notAnalyzable(String reason, MangledSql mangled) {
        return new SqlAnalysisResult(AnalysisStatus.NOT_ANALYZABLE, reason,
                SqlStatementKind.OTHER, null, List.of(),
                mangled == null ? List.of() : mangled.hostVariables(), List.of(),
                mangled == null ? null : mangled.sql(),
                SqlStructureSignals.empty());
    }

    /** Keeps the first syntax error and keeps ANTLR from writing to the console. */
    private static final class ErrorCollector extends BaseErrorListener {

        private String firstMessage;

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                int charPositionInLine, String msg, RecognitionException e) {
            if (firstMessage == null) {
                firstMessage = "column " + charPositionInLine + ": " + msg;
            }
        }
    }
}
