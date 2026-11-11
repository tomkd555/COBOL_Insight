package jp.cobolinsight.core.easytrieve;

import java.util.List;
import java.util.Locale;

/**
 * One Easytrieve statement as the source writes it, continuation lines joined. {@code keyword} is
 * the first word in upper case — an assignment keeps the field it assigns to there, so a reader
 * asks {@link #isAssignment()} — and {@code words} every word of the statement in order: names and
 * keywords in upper case, literals as written with their apostrophes, parentheses and operators as
 * words of their own. {@code depth} is how many IF, DO and CASE blocks enclose the statement.
 *
 * <p>{@code file} and {@code line} are where the statement starts. A statement a macro brought in
 * stands in the macro member, at the member's own line.
 */
public record EasytrieveStatement(String keyword, List<String> words, String file, int line,
        int endLine, int depth) {

    public EasytrieveStatement {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("keyword must not be blank");
        }
        words = List.copyOf(words);
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("file must not be blank");
        }
    }

    /** The statement on one line, its words a blank apart. */
    public String text() {
        return String.join(" ", words);
    }

    /** Whether the keyword is one of the given words. */
    public boolean is(String... keywords) {
        for (String candidate : keywords) {
            if (keyword.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** {@code field = expression} or {@code field EQ expression}: the second word is the operator. */
    public boolean isAssignment() {
        return words.size() >= 3 && (words.get(1).equals("=") || words.get(1).equals("EQ"));
    }

    /** The word at {@code index}, or the empty string past the end. */
    public String word(int index) {
        return index < words.size() ? words.get(index) : "";
    }

    /** Whether any word of the statement is {@code word}, compared without case. */
    public boolean mentions(String word) {
        String wanted = word.toUpperCase(Locale.ROOT);
        for (String candidate : words) {
            if (candidate.equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** The position of the first word equal to {@code word}, or -1. */
    public int indexOf(String word) {
        return words.indexOf(word.toUpperCase(Locale.ROOT));
    }
}
