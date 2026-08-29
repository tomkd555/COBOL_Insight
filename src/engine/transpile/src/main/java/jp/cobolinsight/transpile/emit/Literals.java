package jp.cobolinsight.transpile.emit;

import java.util.Locale;
import java.util.Set;

/** Determination of 88-level VALUE clause value literals, quote removal, and interpretation of figurative constants and THRU ranges. */
public final class Literals {

    /** The word delimiting a VALUE range specification. Matched including the surrounding spaces. */
    private static final String THRU = " THRU ";

    /**
     * Figurative constants whose byte value depends on the code page (EBCDIC/ASCII). Not translated,
     * because mapping them to a value would diverge from the original meaning. Same classification
     * as the operand handling in the procedure division ({@code OperandParser}).
     */
    private static final Set<String> CODE_PAGE_DEPENDENT = Set.of(
            "HIGH-VALUE", "HIGH-VALUES", "LOW-VALUE", "LOW-VALUES", "QUOTE", "QUOTES",
            "NULL", "NULLS");

    private Literals() {
    }

    /** Whether this is a figurative constant that depends on the code page and cannot be mapped to a value. */
    public static boolean isCodePageDependentFigurative(String value) {
        return CODE_PAGE_DEPENDENT.contains(value.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * The value to embed into the comparison expression. Figurative constants map ZERO to 0 and
     * SPACE to a single half-width space; otherwise the unquoted content is returned. Reducing
     * SPACES to a single character matches the procedure division translation.
     */
    public static String resolve(String value) {
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "ZERO", "ZEROS", "ZEROES" -> "0";
            case "SPACE", "SPACES" -> " ";
            default -> unquote(value);
        };
    }

    /**
     * The position of the THRU delimiting a range specification, or -1 if none. A THRU inside quotes
     * is part of a string literal, not a delimiter, and is skipped.
     */
    public static int indexOfThru(String value) {
        String upper = value.toUpperCase(Locale.ROOT);
        char quote = 0;
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (upper.startsWith(THRU, i)) {
                return i;
            }
        }
        return -1;
    }

    /** The character length of the range-specification THRU (including the surrounding spaces). */
    public static int thruLength() {
        return THRU.length();
    }

    /** Whether this is a string literal enclosed in single or double quotes. */
    public static boolean isQuoted(String value) {
        String v = value.trim();
        if (v.length() < 2) {
            return false;
        }
        char first = v.charAt(0);
        char last = v.charAt(v.length() - 1);
        return (first == '\'' || first == '"') && first == last;
    }

    /** Returns the content with quotes removed. Returned as-is if there are no quotes. */
    public static String unquote(String value) {
        String v = value.trim();
        if (isQuoted(v)) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
