package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads out of one program's embedded SQL what R037 and R038 need: the DCLGEN
 * {@code DECLARE ... TABLE} column lists, the query behind each declared cursor, and the
 * column-to-host-variable pairing of a {@code SELECT ... INTO} or {@code FETCH ... INTO}.
 *
 * <p>The pairing is read off the statement text rather than off {@link
 * jp.cobolinsight.core.sql.SqlStatementModel#hostVariables()}, because pairing a host variable
 * with its column needs the select list in order, which the binding list does not carry.
 */
final class Db2Schema {

    /** A name character of a column, a table or a COBOL data name. Japanese data names occur in the field. */
    private static final String NAME = "[\\p{L}\\p{N}$#@_-]";

    private static final Pattern DECLARE_TABLE = Pattern.compile(
            "(?i)^DECLARE\\s+([\\p{L}\\p{N}$#@_.-]+)\\s+TABLE\\s*\\((.*)\\)$");
    private static final Pattern DECLARE_CURSOR = Pattern.compile(
            "(?i)^DECLARE\\s+(" + NAME + "+)\\s+CURSOR\\b(.*)$");
    private static final Pattern COLUMN = Pattern.compile(
            "(?i)^(" + NAME + "+)\\s+(\\p{L}+)\\s*(?:\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\))?(.*)$");
    private static final Pattern HOST_REFERENCE = Pattern.compile(":(" + NAME + "+)");
    private static final Pattern FETCH_CURSOR = Pattern.compile("(?i)^FETCH\\s+"
            + "(?:(?:NEXT|PRIOR|FIRST|LAST|CURRENT|ABSOLUTE|RELATIVE)\\s+)?(?:FROM\\s+)?("
            + NAME + "+)");
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}\\p{N}$#@_.-]+");
    private static final Pattern UPDATE_TABLE =
            Pattern.compile("(?i)^UPDATE\\s+([\\p{L}\\p{N}$#@_.-]+)");
    private static final Pattern INSERT_TABLE =
            Pattern.compile("(?i)^INSERT\\s+INTO\\s+([\\p{L}\\p{N}$#@_.-]+)");
    private static final Pattern HOST_ONLY = Pattern.compile("(?i)^:(" + NAME + "+)"
            + "(?:\\s*(?:INDICATOR\\s+)?:(" + NAME + "+))?$");
    private static final Pattern NOT_NULL = Pattern.compile("(?i)\\bNOT\\s+NULL\\b");
    private static final Pattern PLAIN_COLUMN =
            Pattern.compile("^[\\p{L}\\p{N}$#@_.-]+$");

    private Db2Schema() {
    }

    /** One column of a DCLGEN DECLARE TABLE. {@code length} and {@code scale} are 0 where the type carries none. */
    record Column(String name, String type, int length, int scale, boolean nullable) {
    }

    /** One entry of an INTO clause: the host variable and its indicator, or null where there is none. */
    record Target(String host, String indicator) {
    }

    // ---- blocks ----

    static List<EmbeddedBlock> sqlBlocks(CobolSemanticModel model) {
        List<EmbeddedBlock> blocks = new ArrayList<>();
        for (EmbeddedBlock block : model.embeddedBlocks()) {
            if (block.kind() == EmbeddedBlockKind.SQL) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    static boolean hasCics(CobolSemanticModel model) {
        return model.embeddedBlocks().stream().anyMatch(block -> block.kind().isCics());
    }

    /** The statement with the EXEC SQL wrapper stripped and its layout collapsed to single spaces. */
    static String body(String blockText) {
        String text = blockText.replaceAll("\\s+", " ").trim();
        int exec = text.toUpperCase(Locale.ROOT).indexOf("EXEC SQL");
        if (exec >= 0) {
            text = text.substring(exec + "EXEC SQL".length()).trim();
        }
        int end = text.toUpperCase(Locale.ROOT).lastIndexOf("END-EXEC");
        if (end >= 0) {
            text = text.substring(0, end).trim();
        }
        return text.endsWith(".") ? text.substring(0, text.length() - 1).trim() : text;
    }

    /** The leading SQL keyword of a statement body, uppercased. */
    static String keyword(String body) {
        int space = body.indexOf(' ');
        return (space < 0 ? body : body.substring(0, space)).toUpperCase(Locale.ROOT);
    }

    /**
     * The line the host variable is written on, so a finding points at the clause rather than at
     * the EXEC SQL that opens the statement. The block text keeps one line per source line.
     */
    static int lineOf(EmbeddedBlock block, String hostName) {
        Pattern reference = Pattern.compile(
                "(?i):" + Pattern.quote(hostName) + "(?!" + NAME + ")");
        String[] lines = block.text().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (reference.matcher(lines[i]).find()) {
                return block.range().start().line() + i;
            }
        }
        return block.range().start().line();
    }

    // ---- DCLGEN ----

    /** The DECLARE TABLE column lists of the program, keyed by the unqualified table name in upper case. */
    static Map<String, List<Column>> tables(List<EmbeddedBlock> blocks) {
        Map<String, List<Column>> tables = new LinkedHashMap<>();
        for (EmbeddedBlock block : blocks) {
            Matcher declare = DECLARE_TABLE.matcher(body(block.text()));
            if (!declare.matches()) {
                continue;
            }
            List<Column> columns = new ArrayList<>();
            for (String entry : splitTopLevel(declare.group(2))) {
                Matcher column = COLUMN.matcher(entry.trim());
                if (column.matches()) {
                    columns.add(new Column(column.group(1).toUpperCase(Locale.ROOT),
                            column.group(2).toUpperCase(Locale.ROOT),
                            number(column.group(3)), number(column.group(4)),
                            !NOT_NULL.matcher(column.group(5)).find()));
                }
            }
            if (!columns.isEmpty()) {
                tables.putIfAbsent(unqualified(declare.group(1)), List.copyOf(columns));
            }
        }
        return tables;
    }

    /** The query behind each DECLARE CURSOR of the program, keyed by cursor name in upper case. */
    static Map<String, String> cursorQueries(List<EmbeddedBlock> blocks) {
        Map<String, String> queries = new LinkedHashMap<>();
        for (EmbeddedBlock block : blocks) {
            String body = body(block.text());
            Matcher declare = DECLARE_CURSOR.matcher(body);
            if (declare.matches()) {
                queries.putIfAbsent(declare.group(1).toUpperCase(Locale.ROOT), declare.group(2));
            }
        }
        return queries;
    }

    static String unqualified(String tableName) {
        String name = tableName.toUpperCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    static Column columnOf(List<Column> columns, String name) {
        String wanted = unqualified(name);
        for (Column column : columns) {
            if (column.name().equals(wanted)) {
                return column;
            }
        }
        return null;
    }

    // ---- clauses ----

    /** The select list of a query, split into its entries. Empty when there is no SELECT. */
    static List<String> selectList(String query) {
        String upper = query.toUpperCase(Locale.ROOT);
        int select = wordAt(upper, "SELECT", 0);
        if (select < 0) {
            return List.of();
        }
        int from = select + "SELECT".length();
        int into = wordAt(upper, "INTO", from);
        int table = wordAt(upper, "FROM", from);
        int end = into < 0 ? table : table < 0 ? into : Math.min(into, table);
        if (end < 0) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        for (String entry : splitTopLevel(query.substring(from, end))) {
            entries.add(entry.trim());
        }
        return entries;
    }

    /** The first table named by a FROM clause of the query; null when there is none. */
    static String fromTable(String query) {
        String upper = query.toUpperCase(Locale.ROOT);
        int from = wordAt(upper, "FROM", 0);
        if (from < 0) {
            return null;
        }
        Matcher name = IDENTIFIER.matcher(query.substring(from + "FROM".length()));
        return name.find() ? name.group() : null;
    }

    /** The text of the INTO clause of a SELECT INTO or FETCH INTO; empty when there is none. */
    static String intoClause(String body) {
        String upper = body.toUpperCase(Locale.ROOT);
        int into = wordAt(upper, "INTO", 0);
        if (into < 0) {
            return "";
        }
        int from = wordAt(upper, "FROM", into + "INTO".length());
        return from < 0 ? body.substring(into + "INTO".length())
                : body.substring(into + "INTO".length(), from);
    }

    /**
     * The entries of an INTO clause in order. An indicator is taken from either the
     * {@code :host:ind}, the {@code :host :ind} or the {@code :host INDICATOR :ind} form.
     * Empty when an entry names no host variable, because then nothing can be paired.
     */
    static List<Target> targets(String intoClause) {
        List<Target> targets = new ArrayList<>();
        for (String entry : splitTopLevel(intoClause)) {
            Matcher reference = HOST_REFERENCE.matcher(entry);
            if (!reference.find()) {
                return List.of();
            }
            String host = reference.group(1);
            String indicator = reference.find() ? reference.group(1) : null;
            targets.add(new Target(host, indicator));
        }
        return targets;
    }

    /** A column of a DECLARE TABLE and the host variable that receives it. */
    record Pair(Column column, Target target) {
    }

    /**
     * Lines the columns a {@code SELECT ... INTO} or {@code FETCH ... INTO} reads up with the host
     * variables that receive them. Empty when the statement is neither, when the table has no
     * DECLARE TABLE in the program, or when the two lists cannot be lined up.
     */
    static List<Pair> pairs(String body, Map<String, List<Column>> tables,
            Map<String, String> cursors) {
        String query;
        String keyword = keyword(body);
        if (keyword.equals("SELECT")) {
            query = body;
        } else if (keyword.equals("FETCH")) {
            Matcher cursor = FETCH_CURSOR.matcher(body);
            if (!cursor.find()) {
                return List.of();
            }
            query = cursors.get(cursor.group(1).toUpperCase(Locale.ROOT));
        } else {
            return List.of();
        }
        if (query == null) {
            return List.of();
        }
        String table = fromTable(query);
        List<Column> columns = table == null ? null : tables.get(unqualified(table));
        List<Target> targets = targets(intoClause(body));
        if (columns == null || targets.isEmpty()) {
            return List.of();
        }
        List<String> selected = selectList(query);
        List<Pair> pairs = new ArrayList<>();
        if (selected.size() == 1 && selected.get(0).equals("*")) {
            // A DCLGEN structure receives the whole row; otherwise the columns line up one by one.
            if (targets.size() != 1 && targets.size() != columns.size()) {
                return List.of();
            }
            for (int i = 0; i < columns.size(); i++) {
                pairs.add(new Pair(columns.get(i),
                        targets.size() == 1 ? targets.get(0) : targets.get(i)));
            }
            return pairs;
        }
        if (selected.size() != targets.size()) {
            return List.of();
        }
        for (int i = 0; i < selected.size(); i++) {
            String entry = selected.get(i);
            Column column = isPlainColumn(entry) ? columnOf(columns, entry) : null;
            if (column != null) {
                pairs.add(new Pair(column, targets.get(i)));
            }
        }
        return pairs;
    }

    /**
     * The column-to-host-variable pairs an {@code UPDATE ... SET} or an {@code INSERT ... VALUES}
     * writes. A value that is anything but a bare host variable is left out, because only a bare
     * one is moved into the column unchanged.
     */
    static List<Pair> writePairs(String body, Map<String, List<Column>> tables) {
        String upper = body.toUpperCase(Locale.ROOT);
        Matcher update = UPDATE_TABLE.matcher(body);
        if (update.find()) {
            List<Column> columns = tables.get(unqualified(update.group(1)));
            int set = wordAt(upper, "SET", 0);
            if (columns == null || set < 0) {
                return List.of();
            }
            int where = wordAt(upper, "WHERE", set);
            String clause = where < 0 ? body.substring(set + "SET".length())
                    : body.substring(set + "SET".length(), where);
            List<Pair> pairs = new ArrayList<>();
            for (String assignment : splitTopLevel(clause)) {
                int equals = assignment.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                add(pairs, columns, assignment.substring(0, equals),
                        assignment.substring(equals + 1));
            }
            return pairs;
        }
        Matcher insert = INSERT_TABLE.matcher(body);
        if (!insert.find()) {
            return List.of();
        }
        List<Column> columns = tables.get(unqualified(insert.group(1)));
        String names = parenthesised(body, insert.end());
        int values = wordAt(upper, "VALUES", insert.end());
        if (columns == null || names == null || values < 0) {
            return List.of();
        }
        String literals = parenthesised(body, values + "VALUES".length());
        if (literals == null) {
            return List.of();
        }
        List<String> columnNames = splitTopLevel(names);
        List<String> valueList = splitTopLevel(literals);
        if (columnNames.size() != valueList.size()) {
            return List.of();
        }
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < columnNames.size(); i++) {
            add(pairs, columns, columnNames.get(i), valueList.get(i));
        }
        return pairs;
    }

    private static void add(List<Pair> pairs, List<Column> columns, String name, String value) {
        Column column = columnOf(columns, name.trim());
        Matcher host = HOST_ONLY.matcher(value.trim());
        if (column != null && host.matches()) {
            pairs.add(new Pair(column, new Target(host.group(1), host.group(2))));
        }
    }

    /** The contents of the parenthesis that opens at or after {@code from}; null when there is none. */
    private static String parenthesised(String text, int from) {
        int open = text.indexOf('(', Math.max(0, from));
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            if (text.charAt(i) == '(') {
                depth++;
            } else if (text.charAt(i) == ')' && --depth == 0) {
                return text.substring(open + 1, i);
            }
        }
        return null;
    }

    /** Whether the select-list entry is a plain column name rather than an expression. */
    static boolean isPlainColumn(String entry) {
        return PLAIN_COLUMN.matcher(entry).matches();
    }

    // ---- text scanning ----

    /** Splits on commas that sit outside every parenthesis. */
    static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        parts.removeIf(String::isBlank);
        return parts;
    }

    /** The index of {@code word} as a whole word outside every parenthesis, at or after {@code from}; -1 if none. */
    private static int wordAt(String upper, String word, int from) {
        int depth = 0;
        for (int i = Math.max(0, from); i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && upper.startsWith(word, i)
                    && isBoundary(upper, i - 1) && isBoundary(upper, i + word.length())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isBoundary(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return true;
        }
        char c = text.charAt(index);
        return !(Character.isLetterOrDigit(c) || c == '_' || c == '-');
    }

    private static int number(String digits) {
        return digits == null ? 0 : Integer.parseInt(digits);
    }
}
