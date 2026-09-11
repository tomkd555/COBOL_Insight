package jp.cobolinsight.frontend.sql;

/** Shared logic for scanning SQL text. */
final class SqlTextScanner {

    /**
     * The 1-based column of the fixed-format indicator area. A block that starts here or earlier
     * was never laid out in columns, so it carries no COBOL comment line to find.
     */
    static final int INDICATOR_COLUMN = 7;

    private SqlTextScanner() {
    }

    /**
     * Returns text of the same length (offsets preserved) with every fixed-format COBOL comment
     * line blanked out with spaces. Newlines and carriage returns are kept. The frontend hands the
     * block with the comment lines that stand inside it, and their words would otherwise reach the
     * grammar as identifiers: a Japanese sentence becomes a column and an asterisk in column 7
     * becomes a multiplication.
     *
     * <p>A line counts as one when the first character within the indicator area that is neither a
     * space nor a sequence digit is '*' or '/'. A 'D' debug line is code unless the program is
     * compiled for debugging, so it stays; and {@code /*} or {@code *}{@code /} is the opening or
     * closing of an SQL block comment, which {@link #maskComments} spans on its own.</p>
     */
    static String maskCobolCommentLines(String text) {
        StringBuilder out = new StringBuilder(text);
        int start = 0;
        while (start <= text.length()) {
            int newline = text.indexOf('\n', start);
            int stop = newline < 0 ? text.length() : newline;
            if (isCobolCommentLine(text, start, stop)) {
                for (int i = start; i < stop; i++) {
                    if (out.charAt(i) != '\r') {
                        out.setCharAt(i, ' ');
                    }
                }
            }
            if (newline < 0) {
                break;
            }
            start = newline + 1;
        }
        return out.toString();
    }

    private static boolean isCobolCommentLine(String text, int start, int stop) {
        int limit = Math.min(stop, start + INDICATOR_COLUMN);
        for (int i = start; i < limit; i++) {
            char c = text.charAt(i);
            if (c == ' ' || (c >= '0' && c <= '9')) {
                continue;
            }
            if (c != '*' && c != '/') {
                return false;
            }
            char next = i + 1 < stop ? text.charAt(i + 1) : ' ';
            return !(c == '/' && next == '*') && !(c == '*' && next == '/');
        }
        return false;
    }

    /**
     * Returns the position of the closing quote of a string literal that starts with the quote
     * at position open. Accounts for '' escaping. Returns -1 if unclosed.
     */
    static int findStringEnd(String text, int open) {
        int i = open + 1;
        while (i < text.length()) {
            if (text.charAt(i) == '\'') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * Returns text of the same length (offsets preserved) with the contents of comments
     * ({@code --} line comments and {@code /*} block comments) blanked out with spaces. Newlines
     * are kept. This preprocessing prevents both an apostrophe inside a comment being mistaken
     * for the start of a string literal, and a line comment whose newline was lost to whitespace
     * normalization from swallowing the text that follows it.
     */
    static String maskComments(String text) {
        StringBuilder out = new StringBuilder(text);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'') {
                int close = findStringEnd(text, i);
                i = close < 0 ? text.length() : close + 1;
            } else if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    out.setCharAt(i, ' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                int stop = end < 0 ? text.length() : end;
                for (int j = i + 2; j < stop; j++) {
                    if (out.charAt(j) != '\n') {
                        out.setCharAt(j, ' ');
                    }
                }
                i = end < 0 ? text.length() : end + 2;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Returns the position of the opening quote of the first string literal that never closes, or
     * -1 when every literal of the text closes. What follows such a quote is blanked to the end of
     * the text by {@link #maskStringLiterals}, so a caller that splits on what it reads there has
     * to be able to say that the text, not the split, is what went wrong.
     */
    static int unclosedLiteralAt(String text) {
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) != '\'') {
                i++;
                continue;
            }
            int close = findStringEnd(text, i);
            if (close < 0) {
                return i;
            }
            i = close + 1;
        }
        return -1;
    }

    /** Returns text of the same length (offsets preserved) with the contents of string literals blanked out with spaces. */
    static String maskStringLiterals(String text) {
        StringBuilder out = new StringBuilder(text);
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '\'') {
                int close = findStringEnd(text, i);
                int end = close < 0 ? text.length() : close;
                for (int j = i + 1; j < end; j++) {
                    out.setCharAt(j, ' ');
                }
                i = close < 0 ? text.length() : close + 1;
            } else {
                i++;
            }
        }
        return out.toString();
    }
}
