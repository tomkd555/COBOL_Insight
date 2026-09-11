package jp.cobolinsight.frontend.jcl.instream;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The logical control cards of an in-stream data stream: the lines the author wrote, with the ones
 * that carry on onto the next line joined into one.
 *
 * <p>Two products, two conventions. IDCAMS, the DSN command processor and TSO carry on where a
 * line ends with {@code -} or {@code +}; the sort products carry on where a control statement ends
 * with a comma. Neither reads a blank line, and neither reads a line whose first characters are
 * {@code /*} either.
 *
 * <p>The sort products state a second continuation rule — a card carries on when the next line
 * begins after column 1 — which is not built here, because a shop that indents every control card
 * to column 3, as every fixture of this repository does, would have its whole stream joined into
 * one card by it. The comma rule reads all of them correctly, and a card the column rule alone
 * would have joined is read as two.
 */
final class Cards {

    private Cards() {
    }

    /** Cards joined where the line ends with the continuation character, {@code -} or {@code +}. */
    static List<String> continued(List<String> lines) {
        return join(lines, line -> line.endsWith("-") || line.endsWith("+"), 1);
    }

    /** Cards joined where the control statement ends with a comma. */
    static List<String> comma(List<String> lines) {
        return join(lines, line -> line.endsWith(","), 0);
    }

    /**
     * The same, for a sort product: a line with an asterisk in column 1 is a comment card of
     * DFSORT, SYNCSORT and ICETOOL, and carries no control statement.
     */
    static List<String> sortControl(List<String> lines) {
        return comma(lines.stream().filter(line -> !line.startsWith("*")).toList());
    }

    /** A stream read as one text, and where in that text each card of it began. */
    record Joined(String text, Set<Integer> cardStarts) {
    }

    /**
     * Every card as one string, which is how a free-form utility reads its own input, with the
     * offset each card starts at. A utility whose statements carry no continuation mark tells one
     * statement from the next by the card a verb stands at the start of, so it needs both. Comments
     * are taken off first: a word inside one is no statement of the stream.
     */
    static Joined joined(List<String> lines) {
        StringBuilder out = new StringBuilder();
        Set<Integer> starts = new LinkedHashSet<>();
        for (String line : lines) {
            String text = withoutComment(line).strip();
            if (text.isEmpty() || text.startsWith("/*")) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            starts.add(out.length());
            out.append(text);
        }
        return new Joined(out.toString(), starts);
    }

    /** One card without the {@code --} comment it may end with, apostrophes respected. */
    static String withoutComment(String line) {
        boolean literal = false;
        for (int at = 0; at < line.length(); at++) {
            char c = line.charAt(at);
            if (c == '\'') {
                literal = !literal;
            } else if (!literal && c == '-' && at + 1 < line.length()
                    && line.charAt(at + 1) == '-') {
                return line.substring(0, at);
            }
        }
        return line;
    }

    /**
     * The data set names an operand field holds: one name, or a parenthesised list of them
     * separated by blanks or commas, as DELETE is written in both IDCAMS and TSO. The keywords
     * that follow the name ({@code CLUSTER}, {@code PURGE}) are none of them, and neither is
     * anything after the closing parenthesis of a list.
     */
    static List<String> operandNames(String operands) {
        String text = operands == null ? "" : operands.strip();
        List<String> names = new ArrayList<>();
        if (text.startsWith("(")) {
            int close = text.indexOf(')');
            String inside = close < 0 ? text.substring(1) : text.substring(1, close);
            for (String name : inside.split("[,\\s]+")) {
                if (!name.isBlank()) {
                    names.add(unquote(name));
                }
            }
            return names;
        }
        String first = text.split("\\s+", 2)[0];
        if (!first.isBlank()) {
            names.add(unquote(first));
        }
        return names;
    }

    /** A quoted name without its apostrophes; anything else exactly as written. */
    static String unquote(String text) {
        String value = text.strip();
        return value.length() >= 2 && value.startsWith("'") && value.endsWith("'")
                ? value.substring(1, value.length() - 1) : value;
    }

    /**
     * @param carriesOn whether the stripped line is continued onto the next
     * @param drop how many characters of the continuation mark to leave out of the card
     */
    private static List<String> join(List<String> lines, Predicate<String> carriesOn, int drop) {
        List<String> cards = new ArrayList<>();
        StringBuilder card = new StringBuilder();
        for (String line : lines) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("/*")) {
                continue;
            }
            boolean more = carriesOn.test(text);
            card.append(card.length() == 0 ? "" : " ")
                    .append(more ? text.substring(0, text.length() - drop).strip() : text);
            if (!more) {
                cards.add(card.toString());
                card.setLength(0);
            }
        }
        if (card.length() > 0) {
            cards.add(card.toString());
        }
        return cards;
    }
}
