package jp.cobolinsight.frontend.jcl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a JCL source into logical statements, without deciding what any of them mean.
 *
 * <p>This is the map the parser is measured against. The grammar reads a whole file in one rule,
 * so one unreadable statement can make an enclosing loop give up and take the statements around it
 * with it; the segmenter says which lines carry a statement, so the parser can be told to read
 * them one at a time and so that a statement missing from the model is noticed rather than lost.
 *
 * <p>A statement is a {@code //} line plus the lines it continues onto. Continuation is signalled
 * the way the JCL Reference states it — the operand field ends with a comma, column 72 is not
 * blank, or an apostrophe is still open — and the line that follows must have blanks in columns
 * 3 to 15. Comment lines may sit between continuation lines and are kept with the statement.
 * In-stream data after {@code DD *}, {@code DD DATA} or an {@code XMIT} statement is one statement
 * of its own, and a {@code JOBGROUP} block is one statement from its first line to its
 * {@code ENDGROUP}.
 */
final class JclStatements {

    /** What a statement is, as far as the line it starts on tells. */
    enum Kind {
        JOB, EXEC, DD, PROC, PEND, SET, INCLUDE, IF, ELSE, ENDIF, JCLLIB, OUTPUT, CNTL, ENDCNTL,
        XMIT, GROUP, COMMENT, JECL, NULL, DATA, OTHER
    }

    /** DD statements the job owns rather than a step, which the grammar reads by their name. */
    private static final String JOBLIB = "JOBLIB";
    private static final String SYSCHK = "SYSCHK";

    /** The operations named above, by the word that stands in the operation field. */
    private static final Set<String> OPERATIONS = Set.of("JOB", "EXEC", "DD", "PROC", "PEND",
            "SET", "INCLUDE", "IF", "ELSE", "ENDIF", "JCLLIB", "OUTPUT", "CNTL", "ENDCNTL",
            "XMIT");

    /** The operation that opens a job group, and the one that closes it. */
    private static final String GROUP_OPEN = "JOBGROUP";
    private static final String GROUP_CLOSE = "ENDGROUP";

    /** Operations a job group can never hold: meeting one means its ENDGROUP is missing. */
    private static final Set<String> GROUP_STOPS =
            Set.of("JOB", "EXEC", "DD", "PROC", "PEND");

    /** How many columns a statement is written in: 72 carries the continuation flag, 73-80 a
     * sequence number. */
    private static final int OPERAND_COLUMNS = 71;

    /** DLM= wherever it stands in an operand, whatever case it was written in. */
    private static final Pattern DLM = Pattern.compile("DLM=", Pattern.CASE_INSENSITIVE);

    /**
     * One statement. {@code line} and {@code endLine} are 1-based lines of the source; {@code text}
     * is those lines as written, joined with a newline. {@code concatenatedTo} names the DD a
     * nameless DD is concatenated to, and {@code missingDelimiter} carries the delimiter a DD DATA
     * or XMIT stream never met before the end of the file, empty when the stream was closed.
     */
    record Statement(Kind kind, String name, String concatenatedTo, String missingDelimiter,
            int line, int endLine, String text) {

        /** Whether the statement is a JCL statement, as opposed to a comment, data or other text. */
        boolean isJclStatement() {
            return text.startsWith("//")
                    && kind != Kind.COMMENT && kind != Kind.NULL && kind != Kind.DATA;
        }

        /**
         * Whether the model is expected to hold a row for this statement. JOBLIB and SYSCHK are
         * job-level DD statements with a rule of their own in the grammar; the model keeps both,
         * and a nameless DD concatenated to JOBLIB as well, so all of them are measured against it.
         * A DD concatenated to SYSCHK is the one exception: a checkpoint restart reads one data
         * set, the model keeps one, and a second concatenated to it is not measured against it.
         */
        boolean carriesModel() {
            if (isSyschkConcatenation()) {
                return false;
            }
            return switch (kind) {
                case JOB, EXEC, DD, PROC, SET, INCLUDE, JCLLIB, OUTPUT -> true;
                default -> false;
            };
        }

        /** Whether the statement is the job's own program library, or an entry concatenated to it. */
        boolean isJoblib() {
            return kind == Kind.DD
                    && JOBLIB.equalsIgnoreCase(name.isEmpty() ? concatenatedTo : name);
        }

        /** Whether the statement is the job's own checkpoint data set. */
        boolean isSyschk() {
            return kind == Kind.DD && SYSCHK.equalsIgnoreCase(name);
        }

        /** Whether the statement is a nameless DD concatenated to the checkpoint data set. */
        boolean isSyschkConcatenation() {
            return kind == Kind.DD && name.isEmpty() && SYSCHK.equalsIgnoreCase(concatenatedTo);
        }

        /** The delimiter that ends in-stream data, which is no statement to read on its own. */
        boolean isDelimiter() {
            return kind == Kind.JECL && text.strip().equals("/*");
        }

        /**
         * Whether the operation field is written in lower case, which JCL never is. Only a
         * statement of this file counts: a lower-case line inside a DD DATA or XMIT payload is a
         * program's own input and says nothing about how this JCL was written.
         */
        boolean lowercaseOperation() {
            if (!isJclStatement()) {
                return false;
            }
            String first = field(text.split("\n", 2)[0]);
            if (!first.startsWith("//") || first.startsWith("//*")) {
                return false;
            }
            String operation = rawOperationOf(first.substring(2));
            return !operation.equals(operation.toUpperCase(Locale.ROOT));
        }
    }

    /** A statement's operand: every line's operand field run together, with the offset in the
     * statement's text of each character it holds. */
    record Operand(String text, int[] offsets) {
    }

    /** One line's operand field, and whether a quoted string is still open after it. */
    private record Field(int start, int end, boolean literal) {
    }

    private JclStatements() {
    }

    static List<Statement> of(String text) {
        String[] lines = text.split("\n", -1);
        List<Statement> statements = new ArrayList<>();
        String lastDdName = "";
        int i = 0;
        while (i < lines.length) {
            String body = strip(lines[i]);
            if (field(body).isBlank()) {
                i++;
                continue;
            }
            int start = i;
            Kind kind = kindOf(body);
            String name = nameOf(body);
            if (kind == Kind.COMMENT || kind == Kind.JECL || kind == Kind.NULL
                    || !body.startsWith("//")) {
                statements.add(statement(kind, name, "", "", lines, start, start));
                i++;
                continue;
            }
            if (kind == Kind.GROUP) {
                // The grammar reads a job group in one rule, so the block is one statement: it
                // either parses whole or costs one diagnostic, not one per line.
                int close = groupEnd(lines, start);
                statements.add(statement(kind, name, "", "", lines, start, close));
                i = close + 1;
                continue;
            }
            int end = start;
            // The apostrophe parity is carried through the statement: the line that closes a
            // quoted string has odd parity of its own, and only an open literal keeps it pending.
            Field field = operandField(body, true, false);
            boolean pending = continues(body, field);
            while (pending && end + 1 < lines.length && isContinuation(strip(lines[end + 1]))) {
                end++;
                String next = strip(lines[end]);
                // A comment between continuation lines neither ends the statement nor extends it.
                if (next.startsWith("//*")) {
                    continue;
                }
                field = operandField(next, false, field.literal());
                pending = continues(next, field);
            }
            String concatenatedTo = "";
            if (kind == Kind.DD && name.isEmpty()) {
                concatenatedTo = lastDdName;
            }
            lastDdName = kind == Kind.DD ? (name.isEmpty() ? lastDdName : name) : "";
            statements.add(statement(kind, name, concatenatedTo, "", lines, start, end));
            i = end + 1;
            if (kind == Kind.DD || kind == Kind.XMIT) {
                i = readInStreamData(lines, i, statements, kind,
                        operandOf(joined(lines, start, end)).text());
            }
        }
        return statements;
    }

    /**
     * The same statements over a source of the same shape. The pre-pass rewrites a line without
     * changing its length, so every statement holds the lines it held before and only its text
     * differs; the map is therefore built once, from the source as it was written.
     */
    static List<Statement> rewritten(List<Statement> statements, String text) {
        String[] lines = text.split("\n", -1);
        List<Statement> out = new ArrayList<>();
        for (Statement s : statements) {
            out.add(new Statement(s.kind(), s.name(), s.concatenatedTo(), s.missingDelimiter(),
                    s.line(), s.endLine(), joined(lines, s.line() - 1, s.endLine() - 1)));
        }
        return out;
    }

    /**
     * The in-stream data of each DD statement, by the line the DD statement starts on. The data
     * follows the statement that opened it, so the two are read off the map as a pair; the lines
     * are the source's own, and the delimiter that ended them is not one of them.
     */
    static Map<Integer, List<String>> inStreamData(List<Statement> statements) {
        Map<Integer, List<String>> data = new LinkedHashMap<>();
        for (int at = 1; at < statements.size(); at++) {
            Statement statement = statements.get(at);
            Statement opener = statements.get(at - 1);
            if (statement.kind() == Kind.DATA && opener.kind() == Kind.DD) {
                data.put(opener.line(), List.of(statement.text().split("\n", -1)));
            }
        }
        return data;
    }

    /**
     * A statement's operand: the operand field of its first line and of every line it continues
     * onto, with the offset in {@code statementText} of each character. The offsets are what lets
     * a caller put something else in a parameter's place without moving any other column.
     */
    static Operand operandOf(String statementText) {
        StringBuilder out = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        boolean literal = false;
        boolean first = true;
        int base = 0;
        for (String line : statementText.split("\n", -1)) {
            if (!line.startsWith("//*")) {
                Field field = operandField(line, first, literal);
                for (int at = field.start(); at < field.end(); at++) {
                    out.append(line.charAt(at));
                    offsets.add(base + at);
                }
                literal = field.literal();
                first = false;
            }
            base += line.length() + 1;
        }
        return new Operand(out.toString(), offsets.stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * The last line of a job group: the ENDGROUP that closes it, or the JOBGROUP line alone. A
     * statement of the job itself before ENDGROUP means the block was never closed, and the block
     * then costs itself rather than swallowing the statements written after it.
     */
    private static int groupEnd(String[] lines, int start) {
        for (int at = start + 1; at < lines.length; at++) {
            String body = field(strip(lines[at]));
            if (!body.startsWith("//") || body.startsWith("//*") || body.substring(2).isBlank()) {
                continue;
            }
            String operation = operationOf(body.substring(2));
            if (GROUP_CLOSE.equals(operation)) {
                return at;
            }
            if (GROUP_STOPS.contains(operation)) {
                return start;
            }
        }
        return start;
    }

    /** The in-stream data of a {@code DD *}, a {@code DD DATA} or an XMIT payload. */
    private static int readInStreamData(String[] lines, int from, List<Statement> statements,
            Kind kind, String operand) {
        String folded = operand.toUpperCase(Locale.ROOT);
        // DATA exactly. An operand that merely starts with those four letters is an ordinary
        // keyword parameter such as DATACLAS=, and the lines under it are statements of their own.
        boolean data = kind == Kind.XMIT || folded.equals("DATA")
                || folded.startsWith("DATA,") || folded.startsWith("DATA ");
        if (!data && !folded.startsWith("*")) {
            return from;
        }
        String delimiter = delimiterOf(operand);
        // A DLM= names the one thing that ends the stream, whether the DD writes DATA or an
        // asterisk: with it named, the next JCL statement no longer closes the stream either.
        boolean named = DLM.matcher(operand).find();
        int end = from;
        while (end < lines.length) {
            String body = strip(lines[end]);
            if (body.startsWith(delimiter)) {
                break;
            }
            // A DD * stream also ends where the next JCL statement begins; DD DATA does not, which
            // is what lets a job's own JCL be written as data.
            if (!data && delimiter.equals("/*") && body.startsWith("//")) {
                break;
            }
            end++;
        }
        boolean unterminated = end >= lines.length;
        if (unterminated) {
            // Nothing closed the stream, so it stops at the last line that carries text rather
            // than at the empty line a trailing newline leaves behind.
            while (end > from && strip(lines[end - 1]).isBlank()) {
                end--;
            }
        }
        if (end > from) {
            statements.add(statement(Kind.DATA, "", "", "", lines, from, end - 1));
            // A DD * stream with no DLM= ends at the next statement as well, so the end of the
            // file closes it. Only a stream its delimiter alone can end is left open, and the
            // statement that opened it carries the delimiter so the parser can name it.
            if (unterminated && (data || named)) {
                int at = statements.size() - 2;
                Statement opener = statements.get(at);
                statements.set(at, new Statement(opener.kind(), opener.name(),
                        opener.concatenatedTo(), delimiter, opener.line(), opener.endLine(),
                        opener.text()));
            }
        }
        return end;
    }

    /** The delimiter that ends the stream: the DLM= value when one is named, else {@code /*}. */
    private static String delimiterOf(String operand) {
        Matcher matcher = DLM.matcher(operand);
        if (!matcher.find()) {
            return "/*";
        }
        String value = operand.substring(matcher.end());
        if (value.startsWith("'")) {
            int close = value.indexOf('\'', 1);
            return close > 1 ? value.substring(1, close) : "/*";
        }
        int end = 0;
        while (end < value.length() && value.charAt(end) != ',') {
            end++;
        }
        return end == 0 ? "/*" : value.substring(0, end);
    }

    /** Whether the statement carries on: column 72 written, an open apostrophe, or a comma at the
     * end of the operand field. */
    private static boolean continues(String body, Field field) {
        if (!body.startsWith("//") || body.startsWith("//*")) {
            return false;
        }
        if (body.length() >= 72 && !Character.isWhitespace(body.charAt(71))) {
            return true;
        }
        if (field.literal()) {
            return true;
        }
        return field.end() > field.start() && body.charAt(field.end() - 1) == ',';
    }

    /**
     * The operand field of one line: from where the operand begins to the first blank outside
     * apostrophes, and never past column 71, so the comment a line may carry after its operand is
     * left out. {@code first} says the line still carries the name and operation fields;
     * {@code literal} whether a quoted string was open when the line began.
     */
    private static Field operandField(String body, boolean first, boolean literal) {
        String text = field(body);
        int at = Math.min(2, text.length());
        if (first) {
            at = blanks(text, word(text, at));
            at = blanks(text, word(text, at));
        } else {
            at = blanks(text, at);
        }
        int start = at;
        boolean open = literal;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '\'') {
                open = !open;
            } else if (!open && Character.isWhitespace(c)) {
                break;
            }
            at++;
        }
        return new Field(start, at, open);
    }

    /**
     * A continuation line: {@code //} with blanks in columns 3 to 15 and the operand starting no
     * later than column 16. A comment written between continuation lines belongs to the statement
     * it splits.
     */
    private static boolean isContinuation(String body) {
        String text = field(body);
        if (text.startsWith("//*")) {
            return true;
        }
        if (!text.startsWith("//") || text.substring(2).isBlank()) {
            return false;
        }
        int operand = blanks(text, 2) - 2;
        return operand >= 1 && operand <= 13;
    }

    private static int word(String text, int at) {
        while (at < text.length() && !Character.isWhitespace(text.charAt(at))) {
            at++;
        }
        return at;
    }

    private static int blanks(String text, int at) {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
        return at;
    }

    private static Kind kindOf(String body) {
        String text = field(body);
        if (text.startsWith("//*")) {
            return Kind.COMMENT;
        }
        if (text.startsWith("/*")) {
            return Kind.JECL;
        }
        if (!text.startsWith("//")) {
            return Kind.OTHER;
        }
        String rest = text.substring(2);
        if (rest.isBlank()) {
            return Kind.NULL;
        }
        String operation = operationOf(rest);
        if (GROUP_OPEN.equals(operation)) {
            return Kind.GROUP;
        }
        return OPERATIONS.contains(operation) ? Kind.valueOf(operation) : Kind.OTHER;
    }

    /** The operation field: the second word of the statement, or the first when it has no name. */
    private static String operationOf(String rest) {
        return rawOperationOf(rest).toUpperCase(Locale.ROOT);
    }

    /** The operation field as written, which is what says whether the source is lower case. */
    private static String rawOperationOf(String rest) {
        if (rest.isBlank()) {
            return "";
        }
        String[] words = rest.trim().split("\\s+");
        return Character.isWhitespace(rest.charAt(0)) ? words[0]
                : (words.length > 1 ? words[1] : "");
    }

    private static String nameOf(String body) {
        String text = field(body);
        if (!text.startsWith("//") || text.startsWith("//*")) {
            return "";
        }
        return text.substring(2, word(text, 2));
    }

    /**
     * Columns 1 to 71 of a line. The null statement, the operation field and the operand are all
     * judged there, so a sequence number in columns 73-80 is none of the segmenter's business.
     *
     * <p>A column here is a character index. On the mainframe it is a byte column, so a DBCS
     * operand or comment written before column 72 shifts it; the engine's other column logic is
     * character-based in the same way.
     */
    private static String field(String body) {
        return body.length() > OPERAND_COLUMNS ? body.substring(0, OPERAND_COLUMNS) : body;
    }

    private static Statement statement(Kind kind, String name, String concatenatedTo,
            String missingDelimiter, String[] lines, int start, int end) {
        return new Statement(kind, name, concatenatedTo, missingDelimiter, start + 1, end + 1,
                joined(lines, start, end));
    }

    private static String joined(String[] lines, int start, int end) {
        StringBuilder text = new StringBuilder();
        for (int i = start; i <= end; i++) {
            text.append(i > start ? "\n" : "").append(strip(lines[i]));
        }
        return text.toString();
    }

    /** The line without the carriage return a CRLF source leaves on it. */
    private static String strip(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
