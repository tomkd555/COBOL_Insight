package jp.cobolinsight.frontend.sql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Preprocessing before handing text to the Db2z grammar. Reversibly replaces what the grammar's
 * identifier token does not accept:
 *
 * <ul>
 *   <li>host variables, in every Db2 spelling ({@code :h}, {@code :h:i}, {@code :h :i},
 *       {@code :h INDICATOR :i}, {@code :h.g}, {@code :h OF g}, {@code :h IN g}, with subscripts
 *       left where they stand), by sequential tokens {@code :HV1}, {@code :HV2}, ...;</li>
 *   <li>every identifier outside a string literal that holds a non-ASCII character — a Japanese
 *       table, column or delimited identifier — by sequential tokens {@code NJ1_}, {@code NJ2_},
 *       ... A whole identifier goes into one token, so {@code 商品CD区分} is one token and not
 *       three, and the trailing '_' keeps {@code 在庫1} from mangling to something no restore can
 *       tell from {@code NJ11}.</li>
 * </ul>
 *
 * <p>The same spelling always gets the same token, so there is no collision, and
 * {@link MangledSql#restore} puts every original spelling back. A token the statement already
 * spells for itself is skipped, so a program that writes {@code NJ1_} or {@code :HV1} of its own
 * keeps it. A ':' the mangler cannot read is left as written and recorded in
 * {@link MangledSql#diagnostics()}: the statement is then analysed in degraded form rather than
 * thrown away.</p>
 */
public final class HostVariableMangler {

    /** The runs of layout a name is read across, collapsed so that a data name is one line. */
    private static final Pattern BLANKS = Pattern.compile("\\s+");

    private record ParsedName(String name, int end) {
    }

    public MangledSql mangle(String sqlText) {
        StringBuilder out = new StringBuilder(sqlText.length());
        Map<String, HostVariableReference> byOriginal = new LinkedHashMap<>();
        Map<String, String> runByToken = new LinkedHashMap<>();
        Map<String, String> tokenByRun = new LinkedHashMap<>();
        List<String> diagnostics = new ArrayList<>();
        int[] hostCounter = {0};
        int[] runCounter = {0};
        boolean inDelimited = false;
        int length = sqlText.length();
        int i = 0;
        while (i < length) {
            char c = sqlText.charAt(i);
            if (c == '\'' && !inDelimited) {
                int close = SqlTextScanner.findStringEnd(sqlText, i);
                if (close < 0) {
                    out.append(sqlText, i, length);
                    break;
                }
                out.append(sqlText, i, close + 1);
                i = close + 1;
            } else if (c == '"') {
                // A ':' inside a delimited identifier belongs to the name, and so does an
                // apostrophe: only the non-ASCII names in there are substituted.
                inDelimited = !inDelimited;
                out.append(c);
                i++;
            } else if (c == ':' && !inDelimited) {
                i = appendHostVariable(sqlText, i, out, byOriginal, diagnostics, hostCounter);
            } else if (isIdentifierChar(c)) {
                int end = i;
                boolean nonAscii = false;
                while (end < length && isIdentifierChar(sqlText.charAt(end))) {
                    nonAscii |= isNonAscii(sqlText.charAt(end));
                    end++;
                }
                String run = sqlText.substring(i, end);
                if (!nonAscii) {
                    out.append(run);
                } else {
                    String token = tokenByRun.get(run);
                    if (token == null) {
                        token = nextToken(sqlText, "NJ", "_", runCounter);
                        tokenByRun.put(run, token);
                        runByToken.put(token, run);
                    }
                    out.append(token);
                }
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return new MangledSql(out.toString(), List.copyOf(byOriginal.values()), runByToken,
                diagnostics);
    }

    /**
     * The next token of this shape the statement does not already spell. Skipping what the text
     * carries is what keeps {@link MangledSql#restore} from rewriting an identifier the program
     * wrote itself; the counter only ever moves forward, so no two tokens can meet.
     */
    private static String nextToken(String sqlText, String prefix, String suffix, int[] counter) {
        String token;
        do {
            token = prefix + (++counter[0]) + suffix;
        } while (sqlText.contains(token));
        return token;
    }

    /**
     * Reads one host variable reference starting at the ':' at {@code colon}, appends its token
     * to {@code out} and returns the offset just past it. A reference that cannot be read leaves
     * the ':' as written, records a diagnostic and returns {@code colon + 1}.
     */
    private static int appendHostVariable(String sqlText, int colon, StringBuilder out,
            Map<String, HostVariableReference> byOriginal, List<String> diagnostics,
            int[] counter) {
        ParsedName host = parseName(sqlText, colon + 1);
        if (host == null) {
            diagnostics.add(clue("ホスト変数名として読み取れない ':' があります", sqlText, colon));
            out.append(':');
            return colon + 1;
        }
        String nameError = validateName(host.name());
        if (nameError != null) {
            diagnostics.add(clue("ホスト変数名が不正です。" + nameError, sqlText, colon));
            out.append(':');
            return colon + 1;
        }
        int next = qualifiedEnd(sqlText, host.end());
        // A qualifier may stand on the next line; the data name is the spelling, not the layout.
        String dataName = BLANKS.matcher(sqlText.substring(colon + 1, next))
                .replaceAll(" ").trim();
        String indicator = null;
        int afterIndicator = indicatorStart(sqlText, next);
        if (afterIndicator >= 0) {
            ParsedName ind = parseName(sqlText, afterIndicator);
            if (ind != null && validateName(ind.name()) == null) {
                indicator = ind.name();
                next = ind.end();
            }
        }
        String originalText = sqlText.substring(colon, next);
        HostVariableReference ref = byOriginal.get(originalText);
        if (ref == null) {
            ref = new HostVariableReference(nextToken(sqlText, "HV", "", counter), dataName,
                    indicator, originalText);
            byOriginal.put(originalText, ref);
        }
        out.append(':').append(ref.token());
        return next;
    }

    /**
     * The end of a qualified host variable name: {@code .g}, {@code OF g} and {@code IN g} belong
     * to the name, however many of them follow. {@code IN (} is the SQL predicate, not a
     * qualifier, so it stops the scan — {@link #parseName} finds no name after the parenthesis.
     */
    private static int qualifiedEnd(String sqlText, int from) {
        int end = from;
        while (true) {
            if (end < sqlText.length() && sqlText.charAt(end) == '.') {
                ParsedName qualifier = parseName(sqlText, end + 1);
                if (qualifier == null) {
                    return end;
                }
                end = qualifier.end();
                continue;
            }
            int keyword = skipBlanks(sqlText, end);
            ParsedName word = parseName(sqlText, keyword);
            if (word == null || !isQualifierKeyword(word.name())) {
                return end;
            }
            ParsedName qualifier = parseName(sqlText, skipBlanks(sqlText, word.end()));
            if (qualifier == null) {
                return end;
            }
            end = qualifier.end();
        }
    }

    private static boolean isQualifierKeyword(String word) {
        String upper = word.toUpperCase(Locale.ROOT);
        return upper.equals("OF") || upper.equals("IN");
    }

    /**
     * Where the indicator variable's name starts, or -1 when the reference has no indicator.
     * Both {@code :h :i} and {@code :h INDICATOR :i} are accepted, as is the {@code :h:i} form
     * with nothing in between.
     */
    private static int indicatorStart(String sqlText, int from) {
        int at = skipBlanks(sqlText, from);
        if (at < sqlText.length() && sqlText.charAt(at) == ':') {
            return at + 1;
        }
        ParsedName word = parseName(sqlText, at);
        if (word == null || !word.name().equalsIgnoreCase("INDICATOR")) {
            return -1;
        }
        int colon = skipBlanks(sqlText, word.end());
        return colon < sqlText.length() && sqlText.charAt(colon) == ':' ? colon + 1 : -1;
    }

    private static int skipBlanks(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static ParsedName parseName(String text, int from) {
        int i = from;
        while (i < text.length() && isNameChar(text.charAt(i))) {
            i++;
        }
        if (i == from) {
            return null;
        }
        return new ParsedName(text.substring(from, i), i);
    }

    /** Accepts Japanese data names too, so Unicode letters, digits, hyphen and underscore count as name characters. */
    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    private static boolean isNonAscii(char c) {
        return c > 0x7F;
    }

    /**
     * A character an identifier may be spelled with: the ASCII ones Db2 and COBOL allow, and every
     * non-ASCII letter or digit. The mangler reads a whole run of them at once, so a token stands
     * for a complete name and never runs into the name characters on either side of it — which is
     * what lets {@link MangledSql#restore} tell its own tokens from an identifier the program
     * spells NJn for itself.
     *
     * <p>A hyphen is not one of them. In SQL it is the minus operator, and a run that swallowed it
     * would hide the arithmetic of {@code 在庫数-1} inside a single token and hand the grammar a
     * column of that name. The hyphen a COBOL data name carries belongs to a host variable, which
     * {@link #parseName} reads with {@link #isNameChar} instead.</p>
     */
    static boolean isIdentifierChar(char c) {
        if (isNonAscii(c)) {
            return Character.isLetterOrDigit(c);
        }
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '@';
    }

    /** A COBOL data name never starts or ends with a hyphen and is never all digits. Returns the reason for a violation, or null if valid. */
    private static String validateName(String name) {
        if (name.startsWith("-") || name.endsWith("-")) {
            return "ハイフンで始まる名前・終わる名前は使えません";
        }
        if (name.chars().allMatch(Character::isDigit)) {
            return "数字だけの名前は使えません";
        }
        return null;
    }

    /**
     * The reason with the offending fragment quoted after it. A character offset into the
     * statement is not a place anybody can find in the source, so the fragment stands in for it:
     * up to 20 characters from the ':', on one line. The sentence carries no closing '。' because
     * the caller sets it inside a pair of parentheses of its own.
     */
    private static String clue(String message, String sqlText, int offset) {
        int end = Math.min(sqlText.length(), offset + 20);
        String fragment = BLANKS.matcher(sqlText.substring(offset, end)).replaceAll(" ").trim();
        return message + "。該当箇所は「" + fragment + "」です";
    }
}
