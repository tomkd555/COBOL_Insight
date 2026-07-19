package jp.cobolinsight.sqlfrontend;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
            List<String> tables = tables(stripDb2Clauses(declare.group(2)));
            return analyzed(mangled, SqlStatementKind.DECLARE_CURSOR,
                    declare.group(1), tables, List.of());
        }
        Matcher open = OPEN_CURSOR.matcher(sql);
        if (open.matches()) {
            return analyzed(mangled, SqlStatementKind.OPEN_CURSOR,
                    open.group(1), List.of(), List.of());
        }
        Matcher close = CLOSE_CURSOR.matcher(sql);
        if (close.matches()) {
            return analyzed(mangled, SqlStatementKind.CLOSE_CURSOR,
                    close.group(1), List.of(), List.of());
        }
        Matcher fetch = FETCH.matcher(sql);
        if (fetch.matches()) {
            return analyzed(mangled, SqlStatementKind.FETCH,
                    fetch.group(1), List.of(), restoreTokens(mangled, fetch.group(2)));
        }
        String firstWord = firstWord(sql);
        switch (firstWord) {
            case "SELECT": {
                Matcher into = SELECT_INTO_CLAUSE.matcher(sql);
                if (into.find()) {
                    List<String> intoTargets = restoreTokens(mangled, into.group(1));
                    String withoutInto = into.replaceFirst(" ");
                    List<String> tables = tables(stripDb2Clauses(withoutInto));
                    return analyzed(mangled, SqlStatementKind.SELECT_INTO,
                            null, tables, intoTargets);
                }
                return analyzed(mangled, SqlStatementKind.SELECT,
                        null, tables(stripDb2Clauses(sql)), List.of());
            }
            case "INSERT":
                return analyzed(mangled, SqlStatementKind.INSERT,
                        null, tables(sql), List.of());
            case "UPDATE":
                return analyzed(mangled, SqlStatementKind.UPDATE,
                        null, tables(sql), List.of());
            case "DELETE":
                return analyzed(mangled, SqlStatementKind.DELETE,
                        null, tables(stripDb2Clauses(sql)), List.of());
            default:
                return analyzed(mangled, SqlStatementKind.OTHER,
                        null, List.of(), List.of());
        }
    }

    private static List<String> tables(String sql) throws JSQLParserException {
        return List.copyOf(TablesNamesFinder.findTables(sql));
    }

    /** JSqlParser が構文木に落とさない Db2 固有句を、解析前にテキストから除く。 */
    private static String stripDb2Clauses(String sql) {
        return WITH_UR_CLAUSE.matcher(OPTIMIZE_FOR_CLAUSE.matcher(sql).replaceAll(" "))
                .replaceAll(" ").trim();
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

    private static SqlAnalysisResult analyzed(MangledSql mangled,
            SqlStatementKind kind, String cursorName, List<String> tables, List<String> intoTargets) {
        return new SqlAnalysisResult(AnalysisStatus.ANALYZED, null, kind, cursorName,
                tables, mangled.hostVariables(), intoTargets, mangled.sql());
    }

    private static SqlAnalysisResult notAnalyzable(String reason, MangledSql mangled) {
        return new SqlAnalysisResult(AnalysisStatus.NOT_ANALYZABLE, reason,
                SqlStatementKind.OTHER, null, List.of(),
                mangled == null ? List.of() : mangled.hostVariables(), List.of(),
                mangled == null ? null : mangled.sql());
    }
}
