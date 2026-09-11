package jp.cobolinsight.frontend.jcl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.frontend.jcl.JclStatements.Kind;
import jp.cobolinsight.frontend.jcl.JclStatements.Statement;

/**
 * The pre-pass that puts one JCL source into the shape the lexer reads, without changing the
 * length of any line, so every column stays where the author wrote it.
 *
 * <p>Four shapes are handled. A whole line that belongs to another product — CA7 ({@code #JI}),
 * an ISPF skeleton ({@code )SEL}) or Panvalet ({@code ++INCLUDE}) — becomes a comment line of the
 * same length. A Control-M {@code %%NAME} or CA7 {@code $ONAME} token written inside an operand,
 * and a {@code &NAME} symbol written in a name field, become names the grammar accepts, and
 * {@link #restore(String)} puts the original back into the finished model. A source whose every
 * long line carries a sequence number in columns 73-80 has those columns blanked, and a source
 * written in lower case is folded to upper case outside apostrophes; neither is reported, because
 * both are how the source was filed rather than anything its author did wrong.
 *
 * <p>Comment lines and in-stream data are left exactly as written: what a program reads as data
 * is none of the lexer's business. Which lines carry data is what {@link JclStatements} says, so
 * a {@code DD DATA,DLM=} stream that holds JCL of its own is not mistaken for JCL here.
 *
 * <p>One instance serves one parse, members included, so a placeholder means the same token
 * whichever file it was read from.
 */
final class SchedulerMarkers {

    /** Control-M variables: %%ODATE and its kin. */
    private static final Pattern CONTROL_M = Pattern.compile("%%[A-Za-z][A-Za-z0-9_]*");

    /** CA7 variables: $ODATE and its kin. */
    private static final Pattern CA7 = Pattern.compile("\\$O[A-Z][A-Z0-9]*");

    /** A JCL symbol written in a name field, with the dot that ends the name. */
    private static final Pattern NAME_FIELD_SYMBOL =
            Pattern.compile("&[A-Za-z0-9@#$]{1,8}\\.?");

    /** A line another product owns, recognised by its first column. */
    private static final Pattern FOREIGN_LINE = Pattern.compile("^(#|\\)|\\+\\+).*");

    /** IBM Workload Scheduler directives, already comments as far as the grammar is concerned. */
    private static final Pattern OPC_LINE = Pattern.compile("^//\\*[%>]OPC\\b.*");

    /**
     * {@code //*%OPC SETVAR TDATE=(OYMD1)}: the controller declares TDATE and fills it from its
     * own OYMD1. Both names then stand in the JCL as {@code &NAME} without any SET statement
     * setting them, so both are the scheduler's and neither is a symbol the job left unresolved.
     */
    private static final Pattern OPC_SETVAR = Pattern.compile(
            "^//\\*[%>]OPC\\s+SETVAR\\s+([A-Za-z@#$][A-Za-z0-9@#$]*)\\s*=\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OPC_SETVAR_NAME = Pattern.compile("[A-Za-z@#$][A-Za-z0-9@#$]*");

    /** How much of a directive line is quoted back: enough to say which product wrote it. */
    private static final int MAX_DIRECTIVE_LENGTH = 40;

    /** Columns 73 to 80, as character indices, where a filed source carries its line number. */
    private static final int SEQUENCE_FROM = 72;
    private static final int SEQUENCE_TO = 80;

    /** Column 72, as a character index: the continuation flag, never a character of the operand. */
    private static final int FLAG_COLUMN = 71;

    /** The longest symbol name JCL allows, which is what a placeholder has to fit into. */
    private static final int MAX_SYMBOL_NAME = 8;

    /** How many serials are tried before a stand-in of that length is given up on. */
    private static final int MAX_SERIALS = 64;

    /** What the pre-pass produced: the text to lex, and what it read past on the way. */
    record Result(String text, List<Finding> directives) {
    }

    /** The variables a scheduler directive declares, upper case, in the order they were read. */
    private final Set<String> declaredVariables = new LinkedHashSet<>();
    private final Map<String, String> placeholderByToken = new LinkedHashMap<>();
    private final Map<String, String> tokenByPlaceholder = new LinkedHashMap<>();
    private final Map<String, String> valueByPlaceholder = new LinkedHashMap<>();
    private int serial;

    /**
     * Rewrites one source for the lexer and reports every directive line it read past.
     * {@code statements} is the same source as {@link JclStatements} read it.
     */
    Result apply(String text, String path, List<Statement> statements) {
        Set<Integer> dataLines = dataLinesOf(statements);
        Set<Integer> flagLines = flagLinesOf(statements);
        String[] lines = text.split("\n", -1);
        List<Finding> directives = new ArrayList<>();
        boolean numbered = sequenceNumbered(lines, dataLines);
        boolean lowercase = statements.stream().anyMatch(Statement::lowercaseOperation);
        // A quoted string may be continued onto the next line, so the apostrophe state is carried
        // across the statement the way the segmenter carries it.
        boolean literal = false;
        for (int i = 0; i < lines.length; i++) {
            if (dataLines.contains(i + 1)) {
                literal = false;
                continue;
            }
            String line = lines[i];
            String tail = line.endsWith("\r") ? "\r" : "";
            String body = line.substring(0, line.length() - tail.length());
            if (numbered && body.startsWith("//") && body.length() > SEQUENCE_FROM) {
                body = blankSequence(body);
            }
            if (flagLines.contains(i + 1) && body.length() > FLAG_COLUMN
                    && body.startsWith("//") && !body.startsWith("//*")) {
                body = blankFlag(body);
            }
            if (body.startsWith("//*")) {
                if (OPC_LINE.matcher(body).matches()) {
                    directives.add(directive(path, i + 1, body));
                    declaredBy(body);
                }
            } else if (FOREIGN_LINE.matcher(body).matches()) {
                directives.add(directive(path, i + 1, body));
                literal = false;
                // A line of one or two characters has no room for a comment marker, and a blank
                // line is a line the segmenter reads past just as willingly.
                body = body.length() >= 3 ? "//*" + " ".repeat(body.length() - 3)
                        : " ".repeat(body.length());
            } else {
                boolean jcl = body.startsWith("//");
                if (lowercase && jcl) {
                    body = uppercased(body, literal);
                }
                literal = jcl && literalAfter(body, literal);
                body = substitute(body, text);
            }
            lines[i] = body.concat(tail);
        }
        for (Statement statement : statements) {
            replaceNameFieldSymbol(lines, statement, text);
        }
        return new Result(String.join("\n", lines), directives);
    }

    /**
     * The scheduler tokens and variables this parse has seen, in the order they were read. A
     * variable a SETVAR directive declares is written {@code &NAME} in the JCL, which is the form
     * it takes here.
     */
    List<String> variables() {
        List<String> all = new ArrayList<>(placeholderByToken.keySet());
        declaredVariables.forEach(name -> all.add("&" + name));
        return List.copyOf(all);
    }

    /** Whether a scheduler directive declared that symbol, so no SET statement needs to set it. */
    boolean declares(String name) {
        return declaredVariables.contains(name.toUpperCase(Locale.ROOT));
    }

    /**
     * Takes note of the names a SETVAR directive line names: the variable it declares, and the
     * variables of the controller it is filled from, which stand in parentheses. A value written
     * without them is a literal and names no variable.
     */
    private void declaredBy(String body) {
        Matcher setvar = OPC_SETVAR.matcher(body);
        if (!setvar.matches()) {
            return;
        }
        declaredVariables.add(setvar.group(1).toUpperCase(Locale.ROOT));
        String value = setvar.group(2).strip();
        if (!value.startsWith("(")) {
            return;
        }
        int close = value.indexOf(')');
        Matcher name = OPC_SETVAR_NAME
                .matcher(close < 0 ? value.substring(1) : value.substring(1, close));
        while (name.find()) {
            declaredVariables.add(name.group().toUpperCase(Locale.ROOT));
        }
    }

    /** Whether anything at all stands in the model in place of what the author wrote. */
    boolean isEmpty() {
        return tokenByPlaceholder.isEmpty() && valueByPlaceholder.isEmpty();
    }

    /**
     * Puts every salvaged value and every token back into a string of the finished model. The
     * values go first, because one of them may itself hold a scheduler token that has still to be
     * put back. Only a whole name is replaced, so a longer string that merely holds those
     * characters is left as the author wrote it.
     */
    String restore(String text) {
        if (text == null || isEmpty()) {
            return text;
        }
        String restored = text;
        for (Map.Entry<String, String> entry : valueByPlaceholder.entrySet()) {
            restored = replaceWords(restored, entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : tokenByPlaceholder.entrySet()) {
            restored = replaceWords(restored, entry.getKey(), entry.getValue());
        }
        return restored;
    }

    /**
     * A stand-in of exactly the value's length for a parameter the grammar would not read, so
     * every column of the statement stays where it was, and one the model puts back wherever it
     * kept the value. Null when no free name of that length can be made, which leaves the caller
     * to drop the parameter instead of writing a stand-in it could never undo.
     */
    String salvage(String value, String text) {
        String name = standIn("Z", value.length(), text);
        if (name != null) {
            valueByPlaceholder.put(name, value);
        }
        return name;
    }

    /** The head of a line, cut to {@code max} characters with the ellipsis counted inside the
     * cap and never inside a surrogate pair. */
    static String head(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = max - 1;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    private static Set<Integer> dataLinesOf(List<Statement> statements) {
        Set<Integer> lines = new LinkedHashSet<>();
        for (Statement statement : statements) {
            if (statement.kind() == Kind.DATA) {
                for (int line = statement.line(); line <= statement.endLine(); line++) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    /**
     * Whether every statement long enough to reach column 73 carries digits there. One that does
     * not means the columns hold operand text, and blanking them would lose it. A comment line
     * has no vote, because a banner ruled out to column 80 says nothing about how the file was
     * filed; its own columns 73-80 are blanked all the same once the vote has passed.
     */
    private static boolean sequenceNumbered(String[] lines, Set<Integer> dataLines) {
        boolean any = false;
        for (int i = 0; i < lines.length; i++) {
            String body = lines[i].endsWith("\r")
                    ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            if (dataLines.contains(i + 1) || !body.startsWith("//")
                    || body.startsWith("//*") || body.length() <= SEQUENCE_FROM) {
                continue;
            }
            String columns = body.substring(SEQUENCE_FROM, Math.min(SEQUENCE_TO, body.length()));
            if (!columns.chars().allMatch(Character::isDigit)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    private static String blankSequence(String body) {
        int to = Math.min(SEQUENCE_TO, body.length());
        return body.substring(0, SEQUENCE_FROM) + " ".repeat(to - SEQUENCE_FROM)
                + body.substring(to);
    }

    /**
     * The lines whose column 72 carries the continuation flag: every line of a statement but its
     * last, comment lines excepted. The operand field ends at column 71, so whatever stands in
     * column 72 there says the statement carries on and is no character of the value — a reader
     * who ran the two lines together would read the flag as text.
     */
    private static Set<Integer> flagLinesOf(List<Statement> statements) {
        Set<Integer> lines = new LinkedHashSet<>();
        for (Statement statement : statements) {
            if (!statement.isJclStatement()) {
                continue;
            }
            for (int line = statement.line(); line < statement.endLine(); line++) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** Column 72 blanked, which moves no other column. */
    private static String blankFlag(String body) {
        return body.substring(0, FLAG_COLUMN) + " " + body.substring(FLAG_COLUMN + 1);
    }

    /**
     * Upper case outside apostrophes, which is the case JCL is read in. {@code literal} says
     * whether a quoted string was still open when the line began, so a string continued onto this
     * line keeps the case its author wrote.
     */
    private static String uppercased(String body, boolean literal) {
        StringBuilder out = new StringBuilder(body.length());
        boolean open = literal;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') {
                open = !open;
            }
            out.append(open && c != '\'' ? c : Character.toUpperCase(c));
        }
        return out.toString();
    }

    /** Whether a quoted string is still open after the line. */
    private static boolean literalAfter(String body, boolean literal) {
        boolean open = literal;
        for (int i = 0; i < body.length(); i++) {
            open ^= body.charAt(i) == '\'';
        }
        return open;
    }

    /**
     * Puts a name the grammar accepts in place of a symbol written in the name field of a JOB,
     * EXEC or DD statement, which an ISPF skeleton does and no rule of the grammar reads.
     */
    private void replaceNameFieldSymbol(String[] lines, Statement statement, String text) {
        if (!(statement.kind() == Kind.JOB || statement.kind() == Kind.EXEC
                || statement.kind() == Kind.DD)) {
            return;
        }
        int index = statement.line() - 1;
        String line = lines[index];
        String tail = line.endsWith("\r") ? "\r" : "";
        String body = line.substring(0, line.length() - tail.length());
        // The name field is read out of the line the pre-pass has already rewritten, because a
        // step of it may have changed the line the segmenter measured.
        if (!body.startsWith("//")) {
            return;
        }
        int end = 2;
        while (end < body.length() && !Character.isWhitespace(body.charAt(end))) {
            end++;
        }
        String written = body.substring(2, end);
        if (written.indexOf('&') < 0) {
            return;
        }
        Matcher matcher = NAME_FIELD_SYMBOL.matcher(written);
        StringBuilder name = new StringBuilder();
        int copied = 0;
        while (matcher.find()) {
            name.append(written, copied, matcher.start()).append(nameFor(matcher.group(), text));
            copied = matcher.end();
        }
        name.append(written, copied, written.length());
        // The whole name field is what the model keeps and what is put back, because a stand-in
        // for one symbol of it stands next to the letters around it rather than on its own.
        tokenByPlaceholder.put(name.toString(), written);
        // A symbol JCL leaves no room for — its name runs to eight characters and the dot that
        // ends it makes nine — has a shorter stand-in, so the name field is padded back to the
        // length it was written in and every column after it keeps its place.
        String padding = " ".repeat(Math.max(0, written.length() - name.length()));
        lines[index] = body.substring(0, 2) + name + padding + body.substring(end) + tail;
    }

    private String substitute(String body, String text) {
        return replaceAll(replaceAll(body, CONTROL_M, text), CA7, text);
    }

    private String replaceAll(String body, Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            return body;
        }
        StringBuilder out = new StringBuilder();
        int copied = 0;
        do {
            out.append(body, copied, matcher.start())
                    .append(placeholderFor(matcher.group(), text));
            copied = matcher.end();
        } while (matcher.find());
        return out.append(body, copied, body.length()).toString();
    }

    /**
     * A symbolic parameter of exactly the token's length, so no column moves. The name is one the
     * source does not use itself, so that a SET of the same name cannot resolve the placeholder
     * away before it is put back. A token the grammar leaves no room for is kept as written.
     */
    private String placeholderFor(String token, String text) {
        return placeholderByToken.computeIfAbsent(token, key -> {
            // ponytail: a token longer than nine characters cannot be answered by a symbol, whose
            // name JCL caps at eight; such a token shortens its line. Pad the line if one shows up.
            int length = Math.min(key.length(), MAX_SYMBOL_NAME + 1);
            String name = standIn("C", length - 1, text);
            if (name == null) {
                return key;
            }
            tokenByPlaceholder.put("&" + name, key);
            return "&" + name;
        });
    }

    /**
     * A plain name of exactly the symbol's length, which is what a name field may hold. The symbol
     * is recorded as one this parse has seen; what is put back is the whole name field, which
     * {@link #replaceNameFieldSymbol} registers.
     */
    private String nameFor(String symbol, String text) {
        return placeholderByToken.computeIfAbsent(symbol, key -> {
            String name = standIn("Z", Math.min(key.length(), MAX_SYMBOL_NAME), text);
            return name == null ? key : name;
        });
    }

    /**
     * A name of exactly {@code length} characters made of the prefix, a serial in base 36 and
     * padding, which neither the source nor another stand-in already uses. Null when the length
     * leaves no room for one, which is the caller's cue to do without.
     */
    private String standIn(String prefix, int length, String text) {
        if (length < prefix.length() + 1) {
            return null;
        }
        for (int tries = 0; tries < MAX_SERIALS; tries++) {
            String digits = Integer.toString(++serial, Character.MAX_RADIX)
                    .toUpperCase(Locale.ROOT);
            if (prefix.length() + digits.length() > length) {
                return null;
            }
            String name = prefix + digits
                    + "Z".repeat(length - prefix.length() - digits.length());
            // ponytail: the source checked is the file being rewritten; a name a *member* uses
            // and this file does not would still clash. Compare against every text of the parse
            // if that ever shows up.
            if (!tokenByPlaceholder.containsKey(name) && !tokenByPlaceholder.containsKey("&" + name)
                    && !valueByPlaceholder.containsKey(name)
                    && !placeholderByToken.containsValue(name)
                    && !containsWord(text, name)) {
                return name;
            }
        }
        return null;
    }

    /** Whether the name stands in the text on its own rather than inside a longer name. */
    private static boolean containsWord(String text, String name) {
        for (int at = text.indexOf(name); at >= 0; at = text.indexOf(name, at + 1)) {
            if (!isNameChar(text, at - 1) && !isNameChar(text, at + name.length())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replaces the name where it stands on its own, and leaves every longer name alone. A stand-in
     * that opens with an ampersand is a symbolic parameter, and JCL lets one follow a name
     * character — {@code PREFIX.D&SYM} is an ordinary data set name — so only where such a
     * stand-in ends is a boundary asked for.
     */
    private static String replaceWords(String text, String name, String value) {
        int at = text.indexOf(name);
        if (at < 0) {
            return text;
        }
        boolean opensOnItsOwn = !name.startsWith("&");
        StringBuilder out = new StringBuilder();
        int copied = 0;
        while (at >= 0) {
            if ((!opensOnItsOwn || !isNameChar(text, at - 1))
                    && !isNameChar(text, at + name.length())) {
                out.append(text, copied, at).append(value);
                copied = at + name.length();
                at = text.indexOf(name, copied);
            } else {
                at = text.indexOf(name, at + 1);
            }
        }
        return out.append(text, copied, text.length()).toString();
    }

    /** Whether the character at that offset may stand inside a JCL name. */
    private static boolean isNameChar(String text, int at) {
        if (at < 0 || at >= text.length()) {
            return false;
        }
        char c = text.charAt(at);
        return Character.isLetterOrDigit(c) || c == '@' || c == '#' || c == '$';
    }

    private static Finding directive(String path, int line, String body) {
        return Finding.of(Finding.JCL_DIRECTIVE_RULE_ID, FindingLevel.NOTE,
                "スケジューラーまたはライブラリ管理の指示行として読み飛ばしました（"
                        + head(body.strip(), MAX_DIRECTIVE_LENGTH) + "）。",
                new SourcePosition(path, line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
    }
}
