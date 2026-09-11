package jp.cobolinsight.frontend.sql;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mangled SQL together with its bidirectional map.
 *
 * <p>Not a record: {@link #restore} is called once per fact of a statement, and the map from token
 * to reference is built once here rather than on every call.</p>
 */
public final class MangledSql {

    /** A token of either shape, for restoring a message rather than SQL. */
    private static final Pattern ANY_TOKEN = Pattern.compile(":(HV\\d+)|\\b(NJ\\d+_)");

    private final String sql;
    private final List<HostVariableReference> hostVariables;
    private final Map<String, String> nonAsciiByToken;
    private final List<String> diagnostics;
    private final Map<String, HostVariableReference> byToken;

    /**
     * @param sql             SQL text with host variables replaced by sequential tokens (:HV1,
     *                        :HV2, ...) and every non-ASCII run outside a string literal replaced
     *                        by NJ1_, NJ2_, ...
     * @param hostVariables   host variable correspondences in order of appearance (the same
     *                        original spelling gets the same token)
     * @param nonAsciiByToken the NJn_ tokens and the character run each one stands for, the ASCII
     *                        name characters that ran into the run included
     * @param diagnostics     what the mangler could not read, in the order it met them. A statement
     *                        with a diagnostic is analysed in degraded form rather than rejected.
     */
    public MangledSql(String sql, List<HostVariableReference> hostVariables,
            Map<String, String> nonAsciiByToken, List<String> diagnostics) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.hostVariables = List.copyOf(hostVariables);
        this.nonAsciiByToken = Map.copyOf(nonAsciiByToken);
        this.diagnostics = List.copyOf(diagnostics);
        Map<String, HostVariableReference> map = new HashMap<>();
        for (HostVariableReference reference : this.hostVariables) {
            map.put(reference.token(), reference);
        }
        this.byToken = Map.copyOf(map);
    }

    public String sql() {
        return sql;
    }

    public List<HostVariableReference> hostVariables() {
        return hostVariables;
    }

    public Map<String, String> nonAsciiByToken() {
        return nonAsciiByToken;
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    /** The host variable one {@code HVn} token stands for, or null when no token of this statement. */
    public HostVariableReference reference(String token) {
        return byToken.get(token);
    }

    /** Restores this instance's own SQL text tokens back to their original spellings and returns it. */
    public String restore() {
        return restore(sql);
    }

    /**
     * Restores the tokens (:HVn and NJn_) in arbitrary text back to their original spellings.
     * Does not replace inside string literals; a delimited identifier is read the way the mangler
     * read it, so neither an apostrophe nor a ':' inside one starts anything.
     */
    public String restore(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inDelimited = false;
        int length = text.length();
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            if (c == '\'' && !inDelimited) {
                int close = SqlTextScanner.findStringEnd(text, i);
                if (close < 0) {
                    out.append(text, i, length);
                    break;
                }
                out.append(text, i, close + 1);
                i = close + 1;
            } else if (c == '"') {
                inDelimited = !inDelimited;
                out.append(c);
                i++;
            } else if (c == ':' && !inDelimited) {
                int j = i + 1;
                while (j < length && isTokenChar(text.charAt(j))) {
                    j++;
                }
                HostVariableReference ref = byToken.get(text.substring(i + 1, j));
                if (ref == null) {
                    // Not a token of this statement: step over the ':' alone, so an NJn_ token
                    // standing right after it is still seen.
                    out.append(':');
                    i++;
                } else {
                    out.append(ref.originalText());
                    i = j;
                }
            } else {
                int end = nonAsciiTokenEnd(text, i);
                String original = end < 0 ? null : nonAsciiByToken.get(text.substring(i, end));
                if (original == null) {
                    out.append(c);
                    i++;
                } else {
                    out.append(original);
                    i = end;
                }
            }
        }
        return out.toString();
    }

    /**
     * Restores the tokens in a diagnostic message. A message quotes the input it broke on, and
     * the quoting is the message writer's rather than SQL's, so a token inside a quoted excerpt is
     * put back here where {@link #restore} would step over it.
     */
    public String restoreInMessage(String message) {
        Matcher matcher = ANY_TOKEN.matcher(message);
        StringBuilder out = new StringBuilder(message.length());
        while (matcher.find()) {
            String replacement;
            if (matcher.group(1) != null) {
                HostVariableReference ref = byToken.get(matcher.group(1));
                replacement = ref == null ? matcher.group() : ref.originalText();
            } else {
                String original = nonAsciiByToken.get(matcher.group(2));
                replacement = original == null ? matcher.group() : original;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * The end of an {@code NJn_} token starting at from, or -1 when no such token starts there.
     * The trailing '_' is required, so an identifier ending in a digit ({@code 在庫1} as
     * {@code NJ1_}) cannot be confused with the next token's number; and the character before must
     * not be an identifier character, because the mangler substitutes a whole identifier at a
     * time and so never writes a token there.
     */
    private static int nonAsciiTokenEnd(String text, int from) {
        if (from + 3 >= text.length() || text.charAt(from) != 'N' || text.charAt(from + 1) != 'J') {
            return -1;
        }
        if (from > 0 && HostVariableMangler.isIdentifierChar(text.charAt(from - 1))) {
            return -1;
        }
        int i = from + 2;
        while (i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
            i++;
        }
        return i == from + 2 || i >= text.length() || text.charAt(i) != '_' ? -1 : i + 1;
    }

    /** Tokens eligible for restoration are limited to the :HVn form, so only ASCII alphanumerics count as name characters. */
    private static boolean isTokenChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
