package jp.cobolinsight.frontend.jcl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;

import jp.cobolinsight.core.jcl.JclDataset;
import jp.cobolinsight.core.jcl.JclDisposition;
import jp.cobolinsight.frontend.jcl.gen.JCLParser;

/**
 * The keyword parameters of one JOB, EXEC or DD statement, and the fields the model reads out of
 * them.
 *
 * <p>A parameter is the text the author wrote, cut at the first {@code =}: the keyword before it,
 * the value after it. Cutting the text rather than naming the grammar rule keeps the qualifier a
 * parameter may carry ({@code PARM.STEP1}), which is what an override names its step by. A
 * parameter written with no value at all ({@code DUMMY}, {@code *}, {@code DATA}) maps to the empty
 * string.
 *
 * <p>The text is read off the character stream rather than out of the parse tree, because the
 * lexer hides tokens the value is made of: the apostrophes of a quoted value ({@code PATH='/u/x'})
 * and the commas between DCB subparameters, neither of which the tree's own text carries.
 */
final class JclParameters {

    /** The two spellings of the data set name parameter, in the order they are looked for. */
    static final String[] DSN_KEYWORDS = {"DSN", "DSNAME"};

    /** The data set name that means the DD names no data set at all, the way DUMMY does. */
    static final String NULLFILE = "NULLFILE";

    /** Parameters whose value may point back at a DD written earlier in the job. */
    private static final String REF = "REF=";
    private static final String AFF = "AFF=";

    /** What a referback is written as: a step, a PROC step and a DD name after an asterisk. */
    static final String REFERBACK_PREFIX = "*.";

    /** The parameters whose value is a list an override adds to rather than replaces. */
    private static final Set<String> MERGED_KEYWORDS = Set.of("DCB", "AMP");

    /** The parameter that says the DD names no data set, and the ones it cancels. */
    static final String DUMMY = "DUMMY";
    private static final Set<String> NULLIFIED_BY_DUMMY = Set.of("DSN", "DSNAME", "DISP");

    private JclParameters() {
    }

    /** Adds one parameter of a statement, in the order the statement writes it. */
    static void put(Map<String, String> into, ParserRuleContext ctx) {
        put(into, written(ctx));
    }

    private static void put(Map<String, String> into, String text) {
        int equals = text.indexOf('=');
        String keyword = (equals < 0 ? text : text.substring(0, equals)).strip();
        if (keyword.isEmpty()) {
            return;
        }
        into.put(keyword, equals < 0 ? "" : text.substring(equals + 1).strip());
    }

    /**
     * Every parameter of one statement, in the order it writes them, with the operand the grammar
     * could not read put back where it was written.
     *
     * <p>A rule of this grammar answers an operand it cannot read with an {@code errorChars} node
     * rather than a syntax error, and that node holds whatever the rule before it stopped short of:
     * {@code VOL=SER=(VOL001,VOL002)} leaves the VOL parameter holding {@code SER} alone and the
     * volumes in an errorChars beside it. Such a node is read back into the parameter before it, so
     * the keyword holds the whole of what the author wrote, and the keyword is reported to
     * {@code salvaged} — the value stands in the model, but nothing read it apart.
     */
    static void putAll(Map<String, String> into, List<? extends ParserRuleContext> parameters,
            Consumer<String> salvaged) {
        ParserRuleContext previous = null;
        for (ParserRuleContext parameter : parameters) {
            if (!unreadable(parameter)) {
                put(into, parameter);
                previous = parameter;
                continue;
            }
            if (previous == null) {
                // Nothing before it to belong to: the statement opens with an operand no rule read.
                continue;
            }
            salvage(into, previous.getStart(), parameter.getStop()).ifPresent(salvaged);
        }
    }

    /**
     * Puts an operand the grammar read as error characters back on the keyword whose rule stopped
     * short of it, and answers that keyword. The text runs from the keyword to the end of the error
     * characters, cut where the operand field ends: what follows the first blank outside apostrophes
     * is a comment, whatever the parser made of it.
     */
    private static Optional<String> salvage(Map<String, String> into, Token start, Token stop) {
        String text = operand(written(start, stop));
        int equals = text == null ? -1 : text.indexOf('=');
        if (equals <= 0) {
            return Optional.empty();
        }
        put(into, text);
        return Optional.of(text.substring(0, equals).strip());
    }

    /** One operand field: everything up to the first blank that stands outside apostrophes. */
    private static String operand(String text) {
        if (text == null) {
            return null;
        }
        boolean literal = false;
        for (int at = 0; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c == '\'') {
                literal = !literal;
            } else if (!literal && c == ' ') {
                return text.substring(0, at);
            }
        }
        return text;
    }

    /** Whether the parameter is an operand the grammar answered with an errorChars node. */
    private static boolean unreadable(ParserRuleContext ctx) {
        if (ctx instanceof JCLParser.ErrorCharsContext) {
            return true;
        }
        for (int at = 0; at < ctx.getChildCount(); at++) {
            if (ctx.getChild(at) instanceof JCLParser.ErrorCharsContext) {
                return true;
            }
        }
        return false;
    }

    /** How many characters the operand field of one line holds: columns 1 to 71. */
    private static final int OPERAND_END = 71;

    /**
     * One parameter as the author wrote it, taken from the characters the parser read rather than
     * from the tokens it kept. A parameter written across a continuation is run back together:
     * neither the {@code //} a continuation line opens with nor the blanks up to the column the
     * operand resumes in belong to the value, and a comment line between two continuations belongs
     * to neither.
     */
    private static String written(ParserRuleContext ctx) {
        String text = written(ctx.getStart(), ctx.getStop());
        return text == null ? ctx.getText() : text;
    }

    /** The characters between those two tokens, or null when neither names a place in a stream. */
    private static String written(Token start, Token stop) {
        if (start == null || stop == null || start.getInputStream() == null
                || start.getStartIndex() < 0 || stop.getStopIndex() < start.getStartIndex()) {
            return null;
        }
        return closed(rejoin(start.getInputStream()
                .getText(Interval.of(start.getStartIndex(), stop.getStopIndex())),
                start.getCharPositionInLine()));
    }

    /**
     * The apostrophe that closes a quoted value put back. Where the value is the last thing the
     * parameter holds, the closing apostrophe stands after the parameter's last token rather than
     * between two of them, so the characters the parser read stop one short of it.
     */
    private static String closed(String text) {
        return text.chars().filter(c -> c == '\'').count() % 2 == 1 ? text + "'" : text;
    }

    /**
     * @param column the column the text begins in, so that every line but the last can be cut at
     *     the end of the operand field: column 72 carries the continuation flag and the columns
     *     after it a line number, and neither is a character of the value
     */
    private static String rejoin(String text, int column) {
        if (text.indexOf('\n') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\\r?\\n", -1);
        boolean literal = false;
        for (int at = 0; at < lines.length; at++) {
            String line = lines[at];
            int end = OPERAND_END - (at == 0 ? column : 0);
            if (at + 1 < lines.length && end >= 0 && end < line.length()) {
                line = line.substring(0, end);
            }
            if (at > 0) {
                if (line.startsWith("//*")) {
                    continue;
                }
                line = line.replaceFirst("^//\\s*", "");
            }
            // The operand field of each line ends at the first blank outside apostrophes, so a
            // comment written there — which the lexer hides on a channel of its own but leaves in
            // the character stream — belongs to no parameter.
            String field = line.substring(0, operandEnd(line, literal));
            literal ^= field.chars().filter(c -> c == '\'').count() % 2 == 1;
            out.append(field);
        }
        return out.toString();
    }

    /**
     * Where one line's operand field ends: the first blank that stands outside apostrophes, or the
     * end of the line. {@code literal} says whether a quoted string was open when the line began.
     */
    private static int operandEnd(String text, boolean literal) {
        boolean open = literal;
        for (int at = 0; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c == '\'') {
                open = !open;
            } else if (!open && c == ' ') {
                return at;
            }
        }
        return text.length();
    }

    /**
     * The parameters of a PROC's DD statement with the ones a call wrote over them. A keyword the
     * override names replaces the PROC's, except for the two whose value is a list of
     * subparameters: DCB and AMP are merged subparameter by subparameter, so an override naming
     * {@code BLKSIZE} alone leaves the RECFM and the LRECL the PROC coded.
     *
     * <p>DUMMY and a data set name cancel one another, whichever way round they are written. An
     * override coding DUMMY says the step is to read and write nothing through the DD, so the DSN
     * and the DISP the PROC coded are taken out; an override coding a DSN says it does name a data
     * set after all, so the PROC's DUMMY goes.
     *
     * <p>DSN and DSNAME are one parameter written two ways, so an override coding either of them
     * takes the other out: left standing, the spelling the PROC used would win the lookup over the
     * name the call wrote.
     */
    static Map<String, String> overridden(Map<String, String> written,
            Map<String, String> override) {
        Map<String, String> out = new LinkedHashMap<>(written);
        if (value(override, DSN_KEYWORDS).isPresent()) {
            for (String keyword : DSN_KEYWORDS) {
                if (!override.containsKey(keyword)) {
                    out.remove(keyword);
                }
            }
        }
        override.forEach((keyword, value) -> out.merge(keyword, value,
                (was, now) -> MERGED_KEYWORDS.contains(keyword.toUpperCase(Locale.ROOT))
                        ? mergedSubparameters(was, now) : now));
        if (override.containsKey(DUMMY)) {
            out.keySet().removeAll(NULLIFIED_BY_DUMMY);
        } else if (value(override, DSN_KEYWORDS).isPresent()) {
            out.remove(DUMMY);
        }
        return out;
    }

    /**
     * Two subparameter lists run together: the ones the override names replace the ones of the
     * same name, the rest stay as they were, and the order is the order they were first written in.
     * A list written as one quoted string, which is how AMP is usually coded, is taken apart inside
     * its apostrophes and written back inside them.
     */
    private static String mergedSubparameters(String was, String now) {
        boolean quoted = isOneQuotedString(was) || isOneQuotedString(now);
        Map<String, String> parts = new LinkedHashMap<>();
        subparameters(quoted ? unquote(was.strip()) : was)
                .forEach(part -> parts.put(subparameterKey(part), part));
        subparameters(quoted ? unquote(now.strip()) : now)
                .forEach(part -> parts.put(subparameterKey(part), part));
        String joined = String.join(",", parts.values());
        if (quoted) {
            return "'" + joined + "'";
        }
        return parts.size() > 1 || was.strip().startsWith("(") ? "(" + joined + ")" : joined;
    }

    /** Whether the whole value is one quoted string, apostrophes at both ends and none between. */
    private static boolean isOneQuotedString(String value) {
        String text = value.strip();
        return text.length() >= 2 && text.startsWith("'") && text.endsWith("'")
                && text.indexOf('\'', 1) == text.length() - 1;
    }

    /** The key a subparameter is matched by: its own keyword, or the whole of it when it has none. */
    private static String subparameterKey(String part) {
        String text = unquote(part.strip());
        int equals = text.indexOf('=');
        return (equals < 0 ? text : text.substring(0, equals)).toUpperCase(Locale.ROOT);
    }

    /**
     * One parameter value split on the commas that stand outside parentheses and apostrophes, with
     * the parentheses that may wrap the whole list taken off.
     */
    static List<String> subparameters(String value) {
        String text = value.strip();
        if (text.startsWith("(") && text.endsWith(")")) {
            text = text.substring(1, text.length() - 1);
        }
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean literal = false;
        int start = 0;
        for (int at = 0; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c == '\'') {
                literal = !literal;
            } else if (literal) {
                continue;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, at));
                start = at + 1;
            }
        }
        parts.add(text.substring(start));
        parts.removeIf(String::isBlank);
        return parts;
    }

    /** The value of the first of those keywords the statement carries. */
    static Optional<String> value(Map<String, String> parameters, String... keywords) {
        for (String keyword : keywords) {
            String value = parameters.get(keyword);
            if (value != null && !value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /**
     * The DSN parameter read apart. A referback keeps its own text as the name until
     * {@link Referbacks} resolves it against the step it points at.
     */
    static Optional<JclDataset> dataset(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String value = unquote(text.strip());
        if (value.isBlank()) {
            return Optional.empty();
        }
        if (value.startsWith(REFERBACK_PREFIX)) {
            return Optional.of(new JclDataset(value, Optional.empty(), Optional.empty(), false,
                    Optional.of(value)));
        }
        String name = value;
        Optional<String> member = Optional.empty();
        Optional<Integer> generation = Optional.empty();
        int paren = name.indexOf('(');
        if (paren > 0 && name.endsWith(")")) {
            String inner = name.substring(paren + 1, name.length() - 1).strip();
            name = name.substring(0, paren);
            // A relative generation is (0), (+n) or (-n); every other parenthesis holds a member,
            // an absolute generation (G0001V00) included.
            if (inner.equals("0") || inner.matches("[+-]\\d{1,3}")) {
                generation = Optional.of(Integer.valueOf(inner.startsWith("+")
                        ? inner.substring(1) : inner));
            } else if (!inner.isEmpty()) {
                member = Optional.of(inner);
            }
        }
        return name.isBlank() ? Optional.empty()
                : Optional.of(new JclDataset(name, member, generation, value.startsWith("&&"),
                        Optional.empty()));
    }

    /** The DISP parameter read apart. An omitted status is NEW, which is what JCL assumes. */
    static Optional<JclDisposition> disposition(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String raw = text.strip();
        String inner = raw;
        if (inner.startsWith("(") && inner.endsWith(")")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        String[] parts = inner.split(",", -1);
        String status = parts[0].strip();
        return Optional.of(new JclDisposition(
                status.isEmpty() ? JclDisposition.DEFAULT_STATUS : status,
                part(parts, 1), part(parts, 2), raw));
    }

    /**
     * The parameters written as a referback, against what each points at: {@code *.step.dd} for
     * DSN, REFDD, DCB and VOL=REF, and a plain DD name for UNIT=AFF.
     */
    static Map<String, String> referbacks(Map<String, String> parameters) {
        Map<String, String> found = new LinkedHashMap<>();
        parameters.forEach((keyword, value) -> {
            String target = referbackTarget(keyword, value);
            if (target != null) {
                found.put(keyword, target);
            }
        });
        return found;
    }

    private static String referbackTarget(String keyword, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String name = keyword.toUpperCase(Locale.ROOT);
        String text = value.strip();
        // REFDD copies the attributes of the DD it names, and is written in the same forms DSN is.
        if (name.equals("DSN") || name.equals("DSNAME") || name.equals("REFDD")) {
            return text.startsWith(REFERBACK_PREFIX) ? text : null;
        }
        if (name.equals("DCB")) {
            // DCB=*.STEP1.DD1, or the same referback as the first subparameter of a DCB list.
            String first = text.startsWith("(") ? head(text.substring(1)) : text;
            return first.startsWith(REFERBACK_PREFIX) ? first : null;
        }
        if ((name.equals("VOL") || name.equals("VOLUME")) && text.startsWith(REF)) {
            String reference = text.substring(REF.length());
            return reference.startsWith(REFERBACK_PREFIX) ? reference : null;
        }
        if (name.equals("UNIT") && text.startsWith(AFF)) {
            String reference = head(text.substring(AFF.length()));
            return reference.isBlank() ? null : reference;
        }
        return null;
    }

    /** The head of a subparameter list: everything before the next comma or closing bracket. */
    private static String head(String text) {
        int end = 0;
        while (end < text.length() && text.charAt(end) != ',' && text.charAt(end) != ')') {
            end++;
        }
        return text.substring(0, end);
    }

    /**
     * A quoted value without its apostrophes, and with each doubled apostrophe inside it put back
     * as one. A value that is not one quoted string is left exactly as written.
     */
    static String unquote(String text) {
        if (text == null || text.length() < 2 || !text.startsWith("'") || !text.endsWith("'")) {
            return text;
        }
        return text.substring(1, text.length() - 1).replace("''", "'");
    }

    private static Optional<String> part(String[] parts, int at) {
        if (at >= parts.length) {
            return Optional.empty();
        }
        String value = parts[at].strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
