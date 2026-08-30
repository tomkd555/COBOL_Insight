package jp.cobolinsight.frontend.sql;

/** Shared logic for scanning SQL text. */
final class SqlTextScanner {

    private SqlTextScanner() {
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
