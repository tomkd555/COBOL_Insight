package jp.cobolinsight.sqlfrontend;

import jp.cobolinsight.engineapi.sql.CursorSignals;
import jp.cobolinsight.engineapi.sql.SqlStructureSignals;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第3段。マングリング済みSQLを JSqlParser で解析し、文種別・参照テーブル・
 * ホスト変数(原データ名へ復元済み)を取り出す。
 * OPEN/FETCH/CLOSE と DECLARE CURSOR の外形は JSqlParser の対象外のため正規表現で扱い、
 * DECLARE CURSOR は内側の SELECT を JSqlParser で解析する。
 */
public final class SqlStatementAnalyzer {

    private static final Pattern DECLARE_CURSOR = Pattern.compile(
            "^DECLARE\\s+(\\S+)\\s+CURSOR\\s+(?:WITH\\s+HOLD\\s+)?FOR\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern OPEN_CURSOR = Pattern.compile(
            "^OPEN\\s+(\\S+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSE_CURSOR = Pattern.compile(
            "^CLOSE\\s+(\\S+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FETCH = Pattern.compile(
            "^FETCH\\s+(?:FROM\\s+)?(\\S+)\\s+INTO\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SELECT_INTO_CLAUSE = Pattern.compile(
            "\\bINTO\\s+(:HV\\d+(?:\\s*,\\s*:HV\\d+)*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile(":(HV\\d+)");
    private static final Pattern OPTIMIZE_FOR_CLAUSE = Pattern.compile(
            "\\bOPTIMIZE\\s+FOR\\s+(?:\\d+|:HV\\d+)\\s+ROWS?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WITH_UR_CLAUSE = Pattern.compile(
            "\\bWITH\\s+UR\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FETCH_FIRST_CLAUSE = Pattern.compile(
            "\\bFETCH\\s+FIRST\\s+(?:(?:\\d+|:HV\\d+)\\s+)?ROWS?\\s+ONLY\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOR_READ_ONLY_CLAUSE = Pattern.compile(
            "\\bFOR\\s+READ\\s+ONLY\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOR_FETCH_ONLY_CLAUSE = Pattern.compile(
            "\\bFOR\\s+FETCH\\s+ONLY\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOR_UPDATE_OF_CLAUSE = Pattern.compile(
            "\\bFOR\\s+UPDATE\\s+OF\\s+([A-Za-z0-9_.]+(?:\\s*,\\s*[A-Za-z0-9_.]+)*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FOR_UPDATE_CLAUSE = Pattern.compile(
            "\\bFOR\\s+UPDATE\\b", Pattern.CASE_INSENSITIVE);

    private final HostVariableMangler mangler = new HostVariableMangler();

    public SqlAnalysisResult analyze(SqlBlock block) {
        MangleResult mangleResult = mangler.mangle(block.sqlText());
        if (mangleResult instanceof MangleResult.NotAnalyzable notAnalyzable) {
            return notAnalyzable(notAnalyzable.reason(), null);
        }
        MangledSql mangled = ((MangleResult.Mangled) mangleResult).sql();
        String sql = mangled.sql().replaceAll("\\s+", " ").trim();
        try {
            return dispatch(mangled, sql);
        } catch (JSQLParserException e) {
            return notAnalyzable("JSqlParser が解析できない: " + firstLine(e.getMessage()), mangled);
        }
    }

    private SqlAnalysisResult dispatch(MangledSql mangled, String sql)
            throws JSQLParserException {
        Matcher declare = DECLARE_CURSOR.matcher(sql);
        if (declare.matches()) {
            String cursorName = declare.group(1);
            String body = declare.group(2);
            String stripped = stripDb2Clauses(body);
            CursorSignals cursor = cursorSignals(cursorName, body);
            SqlStructureSignals signals = selectStructure(stripped, mangled, Optional.of(cursor), body);
            return analyzed(mangled, SqlStatementKind.DECLARE_CURSOR,
                    cursorName, tables(stripped), List.of(), signals);
        }
        Matcher open = OPEN_CURSOR.matcher(sql);
        if (open.matches()) {
            return analyzed(mangled, SqlStatementKind.OPEN_CURSOR,
                    open.group(1), List.of(), List.of(), nonSelectStructure(sql));
        }
        Matcher close = CLOSE_CURSOR.matcher(sql);
        if (close.matches()) {
            return analyzed(mangled, SqlStatementKind.CLOSE_CURSOR,
                    close.group(1), List.of(), List.of(), nonSelectStructure(sql));
        }
        Matcher fetch = FETCH.matcher(sql);
        if (fetch.matches()) {
            return analyzed(mangled, SqlStatementKind.FETCH,
                    fetch.group(1), List.of(), restoreTokens(mangled, fetch.group(2)),
                    nonSelectStructure(sql));
        }
        String firstWord = firstWord(sql);
        switch (firstWord) {
            case "SELECT": {
                Matcher into = SELECT_INTO_CLAUSE.matcher(sql);
                if (into.find()) {
                    List<String> intoTargets = restoreTokens(mangled, into.group(1));
                    String withoutInto = into.replaceFirst(" ");
                    String stripped = stripDb2Clauses(withoutInto);
                    SqlStructureSignals signals =
                            selectStructure(stripped, mangled, Optional.empty(), withoutInto);
                    return analyzed(mangled, SqlStatementKind.SELECT_INTO,
                            null, tables(stripped), intoTargets, signals);
                }
                String stripped = stripDb2Clauses(sql);
                SqlStructureSignals signals =
                        selectStructure(stripped, mangled, Optional.empty(), sql);
                return analyzed(mangled, SqlStatementKind.SELECT,
                        null, tables(stripped), List.of(), signals);
            }
            case "INSERT":
                return analyzed(mangled, SqlStatementKind.INSERT,
                        null, tables(sql), List.of(), nonSelectStructure(sql));
            case "UPDATE":
                return analyzed(mangled, SqlStatementKind.UPDATE,
                        null, tables(sql), List.of(), nonSelectStructure(sql));
            case "DELETE":
                return analyzed(mangled, SqlStatementKind.DELETE,
                        null, tables(stripDb2Clauses(sql)), List.of(), nonSelectStructure(sql));
            default:
                return analyzed(mangled, SqlStatementKind.OTHER,
                        null, List.of(), List.of(), nonSelectStructure(sql));
        }
    }

    /** SELECT本体を構文木で走査し、Db2固有句を正規表現で検出して構造シグナルを組み立てる。 */
    private static SqlStructureSignals selectStructure(String selectSql, MangledSql mangled,
            Optional<CursorSignals> cursor, String db2Source) throws JSQLParserException {
        Statement statement = CCJSqlParserUtil.parse(selectSql);
        boolean selectStar = false;
        List<String> nonSargable = List.of();
        List<String> functionOnColumn = List.of();
        if (statement instanceof PlainSelect select) {
            selectStar = SqlStructureInspector.hasSelectStar(select);
            nonSargable = SqlStructureInspector.nonSargablePredicates(select, mangled);
            functionOnColumn = SqlStructureInspector.functionOnColumnPredicates(select, mangled);
        }
        String masked = SqlTextScanner.maskStringLiterals(db2Source);
        return new SqlStructureSignals(selectStar, nonSargable, functionOnColumn, cursor,
                FETCH_FIRST_CLAUSE.matcher(masked).find(),
                OPTIMIZE_FOR_CLAUSE.matcher(masked).find(),
                WITH_UR_CLAUSE.matcher(masked).find());
    }

    /** SELECT系以外の文の構造シグナル。Db2固有句のみを正規表現で検出する。 */
    private static SqlStructureSignals nonSelectStructure(String sql) {
        String masked = SqlTextScanner.maskStringLiterals(sql);
        return new SqlStructureSignals(false, List.of(), List.of(), Optional.empty(),
                FETCH_FIRST_CLAUSE.matcher(masked).find(),
                OPTIMIZE_FOR_CLAUSE.matcher(masked).find(),
                WITH_UR_CLAUSE.matcher(masked).find());
    }

    /** DECLARE CURSOR の FOR READ ONLY / FOR FETCH ONLY / FOR UPDATE OF を正規表現で検出する。 */
    private static CursorSignals cursorSignals(String cursorName, String body) {
        String masked = SqlTextScanner.maskStringLiterals(body);
        List<String> columns = new ArrayList<>();
        Matcher of = FOR_UPDATE_OF_CLAUSE.matcher(masked);
        if (of.find()) {
            for (String column : of.group(1).split(",")) {
                String trimmed = column.trim();
                if (!trimmed.isEmpty()) {
                    columns.add(trimmed);
                }
            }
        }
        return new CursorSignals(cursorName,
                FOR_READ_ONLY_CLAUSE.matcher(masked).find(),
                FOR_FETCH_ONLY_CLAUSE.matcher(masked).find(),
                FOR_UPDATE_CLAUSE.matcher(masked).find(),
                List.copyOf(columns));
    }

    private static List<String> tables(String sql) throws JSQLParserException {
        return List.copyOf(TablesNamesFinder.findTables(sql));
    }

    /**
     * JSqlParser が構文木に落とさない Db2 固有句を、解析前にテキストから除く。
     * FOR UPDATE OF は列並び(OF あり)を先に除いてから、素の FOR UPDATE を除く。
     */
    private static String stripDb2Clauses(String sql) {
        String stripped = OPTIMIZE_FOR_CLAUSE.matcher(sql).replaceAll(" ");
        stripped = WITH_UR_CLAUSE.matcher(stripped).replaceAll(" ");
        stripped = FOR_UPDATE_OF_CLAUSE.matcher(stripped).replaceAll(" ");
        stripped = FOR_UPDATE_CLAUSE.matcher(stripped).replaceAll(" ");
        stripped = FOR_READ_ONLY_CLAUSE.matcher(stripped).replaceAll(" ");
        stripped = FOR_FETCH_ONLY_CLAUSE.matcher(stripped).replaceAll(" ");
        return stripped.trim();
    }

    private static List<String> restoreTokens(MangledSql mangled, String clause) {
        List<String> names = new ArrayList<>();
        Matcher m = TOKEN.matcher(clause);
        while (m.find()) {
            String token = m.group(1);
            mangled.hostVariables().stream()
                    .filter(ref -> ref.token().equals(token))
                    .findFirst()
                    .ifPresent(ref -> names.add(ref.dataName()));
        }
        return List.copyOf(names);
    }

    private static String firstWord(String sql) {
        int space = sql.indexOf(' ');
        String word = space < 0 ? sql : sql.substring(0, space);
        return word.toUpperCase(Locale.ROOT);
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "原因不明";
        }
        int lineBreak = message.indexOf('\n');
        return lineBreak < 0 ? message : message.substring(0, lineBreak).trim();
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
}
