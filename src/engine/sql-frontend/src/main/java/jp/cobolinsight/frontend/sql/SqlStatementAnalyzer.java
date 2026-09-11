package jp.cobolinsight.frontend.sql;

import jp.cobolinsight.core.sql.CursorSignals;
import jp.cobolinsight.core.sql.SqlAnalysis;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;
import jp.cobolinsight.core.sql.SqlStructureSignals;
import jp.cobolinsight.core.sql.WheneverClause;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLLexer;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser;
import jp.cobolinsight.frontend.sql.gen.DB2zSQLParser.SqlStatementContext;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *
 * <p>A statement the grammar will not take is never thrown away. Whatever stops the full parse —
 * a ':' the mangler could not read, an empty block, a syntax error, a statement the grammar
 * recognises as nothing — yields a {@link SqlAnalysis#DEGRADED} result whose kind, tables and
 * cursor name come from a keyword scan, so the kind-level rules and the graph still see it.</p>
 */
public final class SqlStatementAnalyzer {

    /**
     * An SQL identifier, schema qualification included. The same character class as
     * {@code rules/sql/Db2Schema}: Db2 allows # $ @ inside a name, a cursor takes the name of the
     * COBOL data item that declares it, so a hyphen counts, and a Japanese name is ordinary in
     * the field. The two are not one constant because {@code rules} does not compile against
     * this module.
     */
    private static final String NAME_CHARACTER = "[\\p{L}\\p{N}$#@_-]";
    private static final String NAME =
            "\\p{L}" + NAME_CHARACTER + "*(?:\\." + NAME_CHARACTER + "+)*";

    /** A table reference may also be a delimited identifier, which takes anything but a quote. */
    private static final String TABLE_NAME = "(?:" + NAME + "|\"[^\"]+\")";

    /**
     * The correlation name a table may carry, with or without its AS. Only where a comma follows
     * it, because a word standing after the last table of a list is as likely to be the keyword
     * that ends the clause: swallowing it would eat the JOIN of {@code FROM T1 JOIN T2} and lose
     * the table behind it.
     */
    private static final String CORRELATION = "(?:\\s+(?:AS\\s+)?" + NAME + ")?";

    /**
     * One table reference or a comma-separated list of them, as {@code FROM A, B} writes it, each
     * of them with the correlation the older z/OS join style gives it ({@code FROM A X, B Y}).
     */
    private static final String TABLE_LIST =
            TABLE_NAME + "(?:" + CORRELATION + "\\s*,\\s*" + TABLE_NAME + ")*";

    private static final Pattern FIRST_WORD = Pattern.compile("^\\s*([A-Za-z]+)");

    private static final Pattern TABLE_REFERENCE = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN|INTO|UPDATE|MERGE\\s+INTO|LOCK\\s+TABLE"
                    + "|TRUNCATE(?:\\s+TABLE)?|USING)\\s+(" + TABLE_LIST + ")");

    private static final Pattern DECLARED_TABLE =
            Pattern.compile("(?i)\\bDECLARE\\s+(" + NAME + ")\\s+TABLE\\b");

    /**
     * The table a CREATE TABLE names. A declaration the grammar will not take still says which table
     * it is about, and an inventory of the estate is built from that: without it a table whose only
     * declaration carries a clause the grammar refuses is absent from the graph altogether.
     */
    private static final Pattern CREATED_TABLE =
            Pattern.compile("(?i)^\\s*CREATE\\s+TABLE\\s+(" + NAME + ")");

    /**
     * A word a keyword scan can capture where a table or cursor name was expected. OF, WITH, SET,
     * WHERE, DESCRIPTOR and USING are the ones that follow a keyword the scan looks for:
     * {@code FOR UPDATE OF COL} would otherwise book OF as a table, {@code FOR UPDATE WITH RS}
     * WITH. NAMES, LABELS, ANY and BOTH stand where a DESCRIBE writes {@code USING NAMES}, which
     * is the one other place a USING is followed by a word rather than by a table.
     */
    private static final Set<String> RESERVED = Set.of("SELECT", "TABLE", "VALUES", "CURRENT",
            "CURSOR", "FIRST", "NEXT", "PRIOR", "LAST", "BEFORE", "AFTER", "ABSOLUTE", "RELATIVE",
            "ROWSET", "CONTINUE", "OF", "WITH", "SET", "WHERE", "DESCRIPTOR", "USING",
            "ROW", "ROWS", "ONLY", "NAMES", "LABELS", "ANY", "BOTH");

    /**
     * A FETCH down to the cursor it names. Whatever positioning stands between the two —
     * {@code NEXT}, {@code ROWSET}, an {@code ABSOLUTE 5} — belongs to the statement, not to the
     * name, and {@code FETCH NEXT FROM CSR} names a cursor where the table scan would read a
     * table. The anchor is what keeps the {@code FETCH FIRST 10 ROWS ONLY} of a query out of it:
     * a cursor FETCH is a statement of its own and stands at the front.
     */
    private static final String FETCH_HEAD = "^\\s*FETCH\\s+(?:(?:NEXT|PRIOR|FIRST|LAST|CURRENT"
            + "|ABSOLUTE|RELATIVE|ROWSET|[+-]?\\d+)\\s+)*(?:FROM\\s+)?(" + NAME + ")";

    private static final Pattern FETCH_CURSOR = Pattern.compile("(?i)" + FETCH_HEAD);

    private static final Pattern CURSOR_REFERENCE = Pattern.compile("(?i)"
            + "\\bDECLARE\\s+(" + NAME + ")\\s+(?:(?:NO\\s+SCROLL|SCROLL|ASENSITIVE|INSENSITIVE"
            + "|SENSITIVE|STATIC|DYNAMIC)\\s+)*CURSOR\\b"
            + "|\\bOPEN\\s+(" + NAME + ")"
            + "|" + FETCH_HEAD
            + "|\\bCLOSE\\s+(" + NAME + ")"
            + "|\\bALLOCATE\\s+(" + NAME + ")\\s+CURSOR\\b"
            + "|\\bWHERE\\s+CURRENT\\s+OF\\s+(" + NAME + ")");

    private static final Pattern POSITIONED_CURSOR =
            Pattern.compile("(?i)\\bWHERE\\s+CURRENT\\s+OF\\s+(" + NAME + ")");

    private static final Pattern FOR_UPDATE = Pattern.compile("(?i)\\bFOR\\s+UPDATE\\b");

    /** The branches of a MERGE, which say whether it creates, updates or deletes rows. */
    private static final Pattern THEN_INSERT = Pattern.compile("(?i)\\bTHEN\\s+INSERT\\b");
    private static final Pattern THEN_UPDATE = Pattern.compile("(?i)\\bTHEN\\s+UPDATE\\b");
    private static final Pattern THEN_DELETE = Pattern.compile("(?i)\\bTHEN\\s+DELETE\\b");

    /** A LOCK TABLE in SHARE MODE, which takes a read lock and changes no row. */
    private static final Pattern SHARE_MODE = Pattern.compile("(?i)\\bIN\\s+SHARE\\s+MODE\\b");

    /** The word of a DECLARE that says which one it is; a column called CURSOR_ID is not it. */
    private static final Pattern DECLARED_OBJECT =
            Pattern.compile("\\b(CURSOR|TABLE|STATEMENT|VARIABLE)\\b");

    /** The runs of layout the grammar ignores, collapsed so an excerpt stays on one line. */
    private static final Pattern BLANKS = Pattern.compile("\\s+");

    /** The kinds whose first table is the one they write; every other table they name they read. */
    private static final Set<SqlStatementKind> WRITES_ITS_FIRST_TABLE =
            Set.of(SqlStatementKind.INSERT, SqlStatementKind.UPDATE, SqlStatementKind.DELETE,
                    SqlStatementKind.MERGE, SqlStatementKind.LOCK_TABLE,
                    SqlStatementKind.TRUNCATE);

    private final HostVariableMangler mangler = new HostVariableMangler();

    public SqlAnalysisResult analyze(SqlBlock block) {
        String text = statementText(block);
        SqlAnalysisResult result = analyzeStatement(text);
        // WHENEVER is a text read on the statement as the source spells it, so both paths get it.
        result.facts().whenever(WheneverClause.clauseOf(text));
        return result;
    }

    /**
     * The block text with the lines that are not statement text blanked out. A COBOL comment line
     * standing inside the block belongs to the program, and a block that starts in the indicator
     * area or before it — a bare SQL script statement, laid out in no columns at all — carries no
     * line of that shape to blank.
     */
    private static String statementText(SqlBlock block) {
        return block.start().column() > SqlTextScanner.INDICATOR_COLUMN
                ? SqlTextScanner.maskCobolCommentLines(block.sqlText()) : block.sqlText();
    }

    private SqlAnalysisResult analyzeStatement(String text) {
        MangledSql mangled = mangler.mangle(SqlTextScanner.maskComments(text));
        if (!mangled.diagnostics().isEmpty()) {
            return degraded(mangled, mangled.diagnostics().get(0));
        }
        // The grammar ignores layout, but collapsing it keeps the predicate excerpts on one line.
        String sql = BLANKS.matcher(mangled.sql()).replaceAll(" ").trim();
        if (sql.isEmpty()) {
            return degraded(mangled, "EXEC SQL の中身がありません");
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
            // The message quotes the input it broke on, so the tokens inside it go back to the
            // data names the program wrote before a reader ever sees them.
            return degraded(mangled, "Db2 文法が受け付けませんでした: "
                    + mangled.restoreInMessage(errors.firstMessage));
        }
        if (statements.isEmpty()) {
            return degraded(mangled, "SQL 文を認識できませんでした");
        }
        if (statements.size() > 1) {
            // Keeping the first would drop the rest without a word; the keyword scan at least
            // books the tables of all of them.
            return degraded(mangled, "1 つのブロックに SQL 文が " + statements.size()
                    + " 文あります");
        }
        return analyzed(mangled, statements.get(0));
    }

    /** Everything the parse tree of an accepted statement says. */
    private static SqlAnalysisResult analyzed(MangledSql mangled, SqlStatementContext statement) {
        SqlStatementKind kind = SqlFactExtractor.kindOf(statement);
        Optional<CursorSignals> cursor = statement.declareCursorStatement() == null
                ? Optional.empty()
                : Optional.of(SqlStructureInspector.cursorSignals(
                        statement.declareCursorStatement(), mangled));
        // Only a query-shaped statement carries the predicate signals S001 and S002 read, and an
        // INSERT ... SELECT is one: the query it reads its rows from is its own.
        boolean queryShaped = SqlFactExtractor.QUERY_SHAPED.contains(kind);
        SqlStatementModel.Builder facts = new SqlStatementModel.Builder().kind(kind);
        SqlFactExtractor.extract(facts, statement, mangled, kind);
        return new SqlAnalysisResult(SqlAnalysis.FULL, null, mangled.sql(),
                mangled.hostVariables(),
                SqlStructureInspector.signals(statement, mangled, cursor, queryShaped), facts);
    }

    // ---- degraded analysis ----

    /**
     * What a keyword scan can recover from a statement the grammar would not take: the kind, the
     * tables and how they are accessed, the cursor name and whatever the mangler already read.
     *
     * <p>The scan runs over the text with the string literals blanked out, so a
     * {@code 'FROM SHIPPING'} in a predicate does not book a table. The model keeps the text as
     * the mangler left it.</p>
     */
    private static SqlAnalysisResult degraded(MangledSql mangled, String diagnostic) {
        String text = SqlTextScanner.maskStringLiterals(
                BLANKS.matcher(mangled.sql()).replaceAll(" ").trim());
        SqlStatementKind kind = kindOf(text);
        List<String> tables = tablesOf(mangled, text);
        // The scan reads a statement in written order, so the table a DML statement writes is the
        // first it names, and the one a FOR UPDATE cursor may update is the first of its query.
        int target = WRITES_ITS_FIRST_TABLE.contains(kind) && !tables.isEmpty() ? 0 : -1;
        String cursorTable = kind == SqlStatementKind.DECLARE_CURSOR && !tables.isEmpty()
                && FOR_UPDATE.matcher(text).find() ? tables.get(0) : null;
        Map<String, String> access = SqlFactExtractor.tableAccess(kind, tables, target, cursorTable,
                targetLetters(kind, text));
        SqlStatementModel.Builder facts = new SqlStatementModel.Builder()
                .kind(kind)
                .referencedTables(access.isEmpty() ? List.of()
                        : List.copyOf(new LinkedHashSet<>(tables)))
                .cursorName(cursorNameOf(mangled, text))
                .positionedCursor(positionedCursorOf(mangled, text))
                .tableAccess(access)
                .dynamic(kind == SqlStatementKind.PREPARE || kind == SqlStatementKind.EXECUTE
                        || kind == SqlStatementKind.EXECUTE_IMMEDIATE);
        if (kind == SqlStatementKind.INSERT) {
            // A multi-row INSERT is exactly what this grammar will not take, so its row count is
            // read here or nowhere.
            SqlFactExtractor.insertRowset(facts, text, mangled);
        }
        Matcher created = CREATED_TABLE.matcher(text);
        if (kind == SqlStatementKind.DDL && created.find()) {
            // The name only: no column of the declaration was read, and declaredColumns stays empty
            // so that nothing takes this for the column list of the table.
            facts.declaredTable(mangled.restore(created.group(1)));
        }
        return new SqlAnalysisResult(SqlAnalysis.DEGRADED, diagnostic, mangled.sql(),
                mangled.hostVariables(), SqlStructureSignals.empty(), facts);
    }

    /**
     * What the text says about the letters the statement books on the table it writes, where its
     * kind alone does not say: the branches a MERGE carries, and the mode a LOCK TABLE takes. Null
     * where the scan reads nothing of the sort, and the kind decides on its own.
     */
    private static String targetLetters(SqlStatementKind kind, String text) {
        return switch (kind) {
            case MERGE -> SqlFactExtractor.mergeLetters(THEN_INSERT.matcher(text).find(),
                    THEN_UPDATE.matcher(text).find(), THEN_DELETE.matcher(text).find());
            case LOCK_TABLE -> SHARE_MODE.matcher(text).find() ? "R" : null;
            default -> null;
        };
    }

    /** The statement kind read off the leading keywords alone. */
    private static SqlStatementKind kindOf(String text) {
        Matcher first = FIRST_WORD.matcher(text);
        if (!first.find()) {
            return SqlStatementKind.OTHER;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        return switch (first.group(1).toUpperCase(Locale.ROOT)) {
            case "SELECT" -> selectsIntoHostVariables(upper)
                    ? SqlStatementKind.SELECT_INTO : SqlStatementKind.SELECT;
            case "INSERT" -> SqlStatementKind.INSERT;
            case "UPDATE" -> SqlStatementKind.UPDATE;
            case "DELETE" -> SqlStatementKind.DELETE;
            case "MERGE" -> SqlStatementKind.MERGE;
            case "DECLARE" -> declareKindOf(upper);
            case "OPEN" -> SqlStatementKind.OPEN;
            case "FETCH" -> SqlStatementKind.FETCH;
            case "CLOSE" -> SqlStatementKind.CLOSE;
            case "COMMIT" -> SqlStatementKind.COMMIT;
            case "ROLLBACK" -> SqlStatementKind.ROLLBACK;
            case "SAVEPOINT" -> SqlStatementKind.SAVEPOINT;
            case "RELEASE" -> upper.contains(" SAVEPOINT")
                    ? SqlStatementKind.RELEASE_SAVEPOINT : SqlStatementKind.OTHER;
            case "CALL" -> SqlStatementKind.CALL;
            case "LOCK" -> SqlStatementKind.LOCK_TABLE;
            case "SET" -> SqlStatementKind.SET;
            case "VALUES" -> upper.contains(" INTO ")
                    ? SqlStatementKind.VALUES_INTO : SqlStatementKind.OTHER;
            case "PREPARE" -> SqlStatementKind.PREPARE;
            case "EXECUTE" -> upper.startsWith("EXECUTE IMMEDIATE")
                    ? SqlStatementKind.EXECUTE_IMMEDIATE : SqlStatementKind.EXECUTE;
            case "DESCRIBE" -> SqlStatementKind.DESCRIBE;
            case "WHENEVER" -> SqlStatementKind.WHENEVER;
            case "INCLUDE" -> SqlStatementKind.INCLUDE;
            case "BEGIN" -> SqlStatementKind.BEGIN_DECLARE_SECTION;
            case "END" -> SqlStatementKind.END_DECLARE_SECTION;
            case "GET" -> SqlStatementKind.GET_DIAGNOSTICS;
            case "CONNECT" -> SqlStatementKind.CONNECT;
            case "TRUNCATE" -> SqlStatementKind.TRUNCATE;
            case "ALLOCATE" -> SqlStatementKind.ALLOCATE_CURSOR;
            case "ASSOCIATE" -> SqlStatementKind.ASSOCIATE_LOCATORS;
            case "CREATE", "ALTER", "DROP", "GRANT", "REVOKE", "COMMENT", "LABEL", "RENAME" ->
                    SqlStatementKind.DDL;
            default -> SqlStatementKind.OTHER;
        };
    }

    /**
     * Whether a SELECT reads its columns into host variables. Only an INTO standing before the
     * first FROM is that one: {@code SELECT ... FROM T WHERE K IN (SELECT ... INTO ...)} is not a
     * shape Db2 writes, but a scan of broken text meets an INTO behind the FROM often enough.
     */
    private static boolean selectsIntoHostVariables(String upper) {
        int into = upper.indexOf(" INTO ");
        if (into < 0) {
            return false;
        }
        int from = upper.indexOf(" FROM ");
        return from < 0 || into < from;
    }

    /**
     * Which DECLARE it is: the first of CURSOR, TABLE, STATEMENT and VARIABLE the text spells as a
     * word of its own. A column named CURSOR_ID in the list of a DECLARE TABLE is not that word,
     * which is why the match is anchored on both sides rather than searched for as a substring.
     */
    private static SqlStatementKind declareKindOf(String upper) {
        if (upper.contains("GLOBAL TEMPORARY TABLE")) {
            return SqlStatementKind.DDL;
        }
        Matcher declared = DECLARED_OBJECT.matcher(upper);
        if (!declared.find()) {
            return SqlStatementKind.OTHER;
        }
        return switch (declared.group(1)) {
            case "CURSOR" -> SqlStatementKind.DECLARE_CURSOR;
            case "TABLE" -> SqlStatementKind.DECLARE_TABLE;
            case "STATEMENT" -> SqlStatementKind.DECLARE_STATEMENT;
            default -> SqlStatementKind.DECLARE_VARIABLE;
        };
    }

    /**
     * The tables named after FROM, JOIN, INTO, UPDATE, MERGE INTO, USING, LOCK TABLE, TRUNCATE or
     * DECLARE .. TABLE. The cursor of a {@code FETCH ... FROM} is not one of them, so that FROM
     * is taken out of the text before the scan.
     */
    private static List<String> tablesOf(MangledSql mangled, String text) {
        // One entry per place the text names a table, duplicates kept: an INSERT that reads the
        // table it writes has to reach tableAccess as two occurrences to earn both letters.
        List<String> names = new ArrayList<>();
        Matcher declared = DECLARED_TABLE.matcher(text);
        while (declared.find()) {
            names.add(mangled.restore(declared.group(1)));
        }
        Matcher referenced = TABLE_REFERENCE.matcher(FETCH_CURSOR.matcher(text).replaceFirst(""));
        while (referenced.find()) {
            for (String name : splitTableList(referenced.group(1))) {
                if (!isReserved(name)) {
                    names.add(mangled.restore(name));
                }
            }
        }
        return names;
    }

    /**
     * One table list split into its names, each without the correlation that may follow it. Only a
     * comma outside a delimited identifier separates two of them: {@code "A, B"} is one table whose
     * name holds a comma.
     */
    private static List<String> splitTableList(String list) {
        List<String> names = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                names.add(withoutCorrelation(list.substring(start, i).trim()));
                start = i + 1;
            }
        }
        names.add(withoutCorrelation(list.substring(start).trim()));
        return names;
    }

    /**
     * One entry of a table list without its correlation name: {@code T1 A} and {@code T1 AS A} both
     * name T1. A delimited identifier holds blanks of its own, so the cut is looked for behind its
     * closing quote.
     */
    private static String withoutCorrelation(String entry) {
        int end = Math.max(0, entry.startsWith("\"") ? entry.indexOf('"', 1) + 1 : 0);
        while (end < entry.length() && !Character.isWhitespace(entry.charAt(end))) {
            end++;
        }
        return entry.substring(0, end);
    }

    /** The cursor a DECLARE, OPEN, FETCH, CLOSE, ALLOCATE or WHERE CURRENT OF names. */
    private static String cursorNameOf(MangledSql mangled, String text) {
        Matcher m = CURSOR_REFERENCE.matcher(text);
        while (m.find()) {
            for (int group = 1; group <= m.groupCount(); group++) {
                String name = m.group(group);
                if (name != null && !isReserved(name)) {
                    return mangled.restore(name);
                }
            }
        }
        return null;
    }

    /** The cursor a positioned UPDATE or DELETE works through. */
    private static String positionedCursorOf(MangledSql mangled, String text) {
        Matcher m = POSITIONED_CURSOR.matcher(text);
        return m.find() && !isReserved(m.group(1)) ? mangled.restore(m.group(1)) : null;
    }

    private static boolean isReserved(String name) {
        return RESERVED.contains(name.toUpperCase(Locale.ROOT));
    }

    /** Keeps the first syntax error and keeps ANTLR from writing to the console. */
    private static final class ErrorCollector extends BaseErrorListener {

        /**
         * How much of the ANTLR message is kept. "expecting {...}" lists every token of a grammar
         * with two thousand of them, and the diagnostic has to fit a finding message and a table
         * cell; the head of it is what says which construct broke.
         */
        private static final int MESSAGE_LIMIT = 160;

        private String firstMessage;

        /**
         * The message goes out as ANTLR wrote it, in English. The position ANTLR reports is a
         * column of the collapsed text the grammar was handed, which is nowhere the reader can
         * look, and the message already quotes the input that broke.
         */
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                int charPositionInLine, String msg, RecognitionException e) {
            if (firstMessage == null) {
                firstMessage = shorten(msg);
            }
        }

        private static String shorten(String message) {
            return message.length() <= MESSAGE_LIMIT ? message
                    : message.substring(0, MESSAGE_LIMIT) + " …";
        }
    }
}
