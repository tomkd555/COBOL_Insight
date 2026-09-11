package jp.cobolinsight.frontend.sql;

import jp.cobolinsight.core.source.FixedFormatColumns;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits an SQL script — DDL, a native SQL PL routine, SPUFI or DSNTEP2 input — into the statements
 * it holds. The entry point the pipeline uses for an asset whose whole content is SQL; an
 * {@code EXEC SQL} block of a COBOL source arrives as one statement already and never comes here.
 *
 * <p>A statement ends at the terminator, which is {@code ;} until a {@code --#SET TERMINATOR @} or
 * {@code TERMINATOR @} line names another one — what SPUFI and DSNTEP2 write so that a routine body
 * may hold semicolons of its own. A terminator inside a string literal, inside a {@code --} line
 * comment, inside a {@code /* *}{@code /} block comment or inside a compound statement does not end
 * anything.
 *
 * <p>The compound statement is counted, not recognised: {@code BEGIN} and {@code CASE} open a block
 * and an {@code END} closes one, taking its suffix with it — so a {@code CREATE PROCEDURE},
 * {@code CREATE FUNCTION} or {@code CREATE TRIGGER} whose body is {@code BEGIN … END} comes out as
 * one statement however deeply it nests. {@code IF … END IF} and the loop forms
 * ({@code WHILE … END WHILE} and the rest) are left out of the count on both sides, which balances
 * without having to tell a {@code FOR} that opens a loop from the {@code FOR EACH ROW} of a trigger.
 *
 * <p>A statement's text is the script's own text from its first character through its last, with the
 * terminator and the comments standing before and after it left out — so the position reported for a
 * statement names the line its SQL starts on rather than the banner above it. A comment written
 * between two clauses of one statement stays where it is.
 */
public final class SqlScriptSplitter {

    /**
     * One statement of a script. Lines and columns are 1-based and count characters, and they point
     * at the first and the last character of {@link #text}.
     */
    public record Statement(String text, int startLine, int startColumn, int endLine,
            int endColumn) {
    }

    /** The terminator a script uses until a directive names another one. */
    private static final char DEFAULT_TERMINATOR = ';';

    /**
     * The line that changes the terminator. DSNTEP4 writes it as a comment
     * ({@code --#SET TERMINATOR @}); SPUFI's own control line writes the word on its own.
     */
    private static final Pattern TERMINATOR_DIRECTIVE = Pattern.compile(
            "^\\s*(?:--\\s*#?\\s*SET\\s+)?TERMINATOR\\s+(\\S)\\s*$", Pattern.CASE_INSENSITIVE);

    /** The words that open a block whose closing END this splitter counts. */
    private static final Set<String> BLOCK_OPENERS = Set.of("BEGIN", "CASE");

    /**
     * The words that may follow END, and belong to it. Reading one of them on its own would open a
     * block the END has just closed, so END takes its suffix with it. Of the six, only CASE closes a
     * block the count opened; IF and the loop forms are left out on both sides.
     */
    private static final Set<String> BLOCK_SUFFIXES =
            Set.of("IF", "WHILE", "FOR", "LOOP", "REPEAT", "CASE");

    /** The byte length of a card image, which is where a sequence number may reach. */
    private static final int CARD_BYTES = 80;

    private SqlScriptSplitter() {
    }

    /**
     * The statements of one script, in the order it writes them, where a character counts as one
     * byte. Only a caller with no decoded source is in that position; the pipeline passes the byte
     * offsets so that the card-image columns of a double-byte script come out right.
     */
    public static List<Statement> split(String script) {
        return split(script, index -> index);
    }

    /**
     * The statements of one script, with {@code byteOffsetOfChar} giving the offset in the source's
     * own bytes of the character at an index — the whole byte length for the index one past the end.
     * A card image places its sequence number in byte columns 73 to 80, and a Japanese literal in
     * the statement makes the character column and the byte column two different numbers.
     */
    public static List<Statement> split(String script, IntUnaryOperator byteOffsetOfChar) {
        int[] lineStarts = lineStartsOf(script);
        String text = withoutSequenceArea(script, lineStarts, byteOffsetOfChar);
        // The directive lines go, so that neither the statement text nor the terminator search
        // sees them; what they said is kept as the terminator in force from the end of that line.
        Map<Integer, Character> terminatorFrom = new TreeMap<>();
        StringBuilder kept = new StringBuilder(text);
        for (int line = 0; line < lineStarts.length; line++) {
            int from = lineStarts[line];
            int to = lineEnd(text, lineStarts, line);
            Matcher directive = TERMINATOR_DIRECTIVE.matcher(text.substring(from, to));
            if (directive.matches()) {
                terminatorFrom.put(to, directive.group(1).charAt(0));
                blank(kept, from, to);
            }
        }
        String body = kept.toString();
        String masked = withoutCommentDelimiters(
                SqlTextScanner.maskStringLiterals(SqlTextScanner.maskComments(body)));

        List<Statement> statements = new ArrayList<>();
        char terminator = DEFAULT_TERMINATOR;
        int depth = 0;
        int start = -1;
        int end = -1;
        for (int i = 0; i < masked.length(); i++) {
            Character named = terminatorFrom.get(i);
            if (named != null) {
                terminator = named;
            }
            char c = masked.charAt(i);
            if (c == terminator && depth == 0) {
                if (start >= 0) {
                    statements.add(statementOf(body, lineStarts, start, end));
                    start = -1;
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (start < 0) {
                start = i;
            }
            end = i + 1;
            if (Character.isLetter(c)
                    && (i == 0 || !isNameCharacter(masked.charAt(i - 1), terminator))) {
                Block block = blockAfter(depth, masked, i, wordAt(masked, i, terminator),
                        terminator);
                depth = block.depth();
                i = block.readTo();
                end = i + 1;
            }
        }
        // A script whose last statement carries no terminator still holds that statement.
        if (start >= 0) {
            statements.add(statementOf(body, lineStarts, start, end));
        }
        return statements;
    }

    /**
     * The 1-based line a string literal with no closing quote starts on, or empty for a script
     * whose literals all close. Such a quote blanks the rest of the script for the terminator
     * scan, so the statements behind it are lost; the caller reports the line rather than leaving
     * the reader with fewer statements than the script writes. A literal may span lines, so the
     * scan cannot simply stop at the end of the line and call the rest closed.
     */
    public static OptionalInt unclosedLiteralLine(String script) {
        int at = SqlTextScanner.unclosedLiteralAt(SqlTextScanner.maskComments(script));
        return at < 0 ? OptionalInt.empty() : OptionalInt.of(lineOf(lineStartsOf(script), at));
    }

    /**
     * The compound body of one statement, and how many lines down from the statement's own first
     * line the body begins.
     */
    public record CompoundBody(String text, int lineOffset) {
    }

    /**
     * What stands between a statement's outermost {@code BEGIN} — or the {@code CASE} a CASE
     * statement opens with — and the {@code END} that closes it, or empty for a statement that is
     * no compound. The closing END is found by counting, so a body that ends {@code END CASE} or a
     * labelled {@code END L1} is read to its real end, and a body holding a block of its own hands
     * that block back whole for the caller to read the same way.
     */
    public static Optional<CompoundBody> compoundBody(String statement) {
        String masked = withoutCommentDelimiters(
                SqlTextScanner.maskStringLiterals(SqlTextScanner.maskComments(statement)));
        int depth = 0;
        int from = -1;
        for (int i = 0; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (!Character.isLetter(c)
                    || (i > 0 && isNameCharacter(masked.charAt(i - 1), DEFAULT_TERMINATOR))) {
                continue;
            }
            String word = wordAt(masked, i, DEFAULT_TERMINATOR);
            Block block = blockAfter(depth, masked, i, word, DEFAULT_TERMINATOR);
            if (from < 0 && depth == 0 && block.depth() == 1 && opensABody(masked, i, word)) {
                from = afterAtomic(masked, block.readTo() + 1);
            } else if (from >= 0 && depth == 1 && block.depth() == 0) {
                return Optional.of(new CompoundBody(statement.substring(from, i),
                        lineBreaksBefore(statement, from)));
            }
            depth = block.depth();
            i = block.readTo();
        }
        return Optional.empty();
    }

    /**
     * Whether the word opens a body whose statements are read one at a time: a BEGIN wherever it
     * stands, and a CASE only where the statement starts with it. A CASE standing anywhere else is
     * the expression form — {@code SET V = CASE WHEN … END} — whose branches are values rather
     * than statements, and opening it would cost the statement that holds it.
     */
    private static boolean opensABody(String masked, int at, String word) {
        return "BEGIN".equalsIgnoreCase(word)
                || "CASE".equalsIgnoreCase(word) && masked.substring(0, at).isBlank();
    }

    /** Where the body starts once the {@code ATOMIC} or {@code NOT ATOMIC} of a BEGIN is passed. */
    private static int afterAtomic(String masked, int at) {
        int word = wordStartAfter(masked, at);
        if (word < 0) {
            return at;
        }
        String upper = wordAt(masked, word, DEFAULT_TERMINATOR).toUpperCase(Locale.ROOT);
        if ("NOT".equals(upper)) {
            return afterAtomic(masked, word + upper.length());
        }
        return "ATOMIC".equals(upper) ? word + upper.length() : at;
    }

    /** How many line breaks stand before an offset, which is how far down from line 1 it is. */
    private static int lineBreaksBefore(String text, int offset) {
        int[] starts = lineStartsOf(text);
        return lineOf(starts, offset) - 1;
    }

    /** How deep the nesting is once a word is read, and the index of the last character read. */
    private record Block(int depth, int readTo) {
    }

    /**
     * The nesting once the word at {@code at} has been read. An END takes its suffix with it, so
     * {@code END CASE} closes one block instead of closing one and opening another, and a labelled
     * {@code END L1} closes one and leaves the label to be read as the ordinary word it is.
     */
    private static Block blockAfter(int depth, String masked, int at, String word, char terminator) {
        int readTo = at + word.length() - 1;
        String upper = word.toUpperCase(Locale.ROOT);
        if (BLOCK_OPENERS.contains(upper)) {
            return new Block(depth + 1, readTo);
        }
        if (!"END".equals(upper)) {
            return new Block(depth, readTo);
        }
        int suffixAt = wordStartAfter(masked, readTo + 1);
        String suffix = suffixAt < 0 ? ""
                : wordAt(masked, suffixAt, terminator).toUpperCase(Locale.ROOT);
        if (BLOCK_SUFFIXES.contains(suffix)) {
            // END IF and the loop forms close a block the count never opened; END CASE closes one.
            return new Block("CASE".equals(suffix) ? closed(depth) : depth,
                    suffixAt + suffix.length() - 1);
        }
        return new Block(closed(depth), readTo);
    }

    /**
     * One block closed. A script the splitter reads wrong must still end its statements, so the
     * count stops at zero rather than going negative and swallowing the rest of the file.
     */
    private static int closed(int depth) {
        return Math.max(0, depth - 1);
    }

    /**
     * The masked text with the delimiters of a block comment blanked as well, so a comment leaves
     * nothing behind for the scan to read. {@link SqlTextScanner#maskComments} blanks what stands
     * between {@code /*} and {@code *}{@code /} and leaves the two delimiters, which would otherwise
     * open a statement of their own after the last terminator and move the position of a statement
     * to the banner above it. Whatever pair survives both maskings is a real delimiter: one written
     * inside a string literal was blanked with the literal.
     */
    private static String withoutCommentDelimiters(String masked) {
        return masked.replace("/*", "  ").replace("*/", "  ");
    }

    /** One statement, its text taken from the script and its ends turned into line and column. */
    private static Statement statementOf(String body, int[] lineStarts, int start, int end) {
        int startLine = lineOf(lineStarts, start);
        int endLine = lineOf(lineStarts, end - 1);
        return new Statement(body.substring(start, end), startLine,
                start - lineStarts[startLine - 1] + 1, endLine, end - lineStarts[endLine - 1]);
    }

    /**
     * The script with the identification area of a card image blanked out. A SPUFI member carries a
     * sequence number in byte columns 73 to 80, and the number of the line a terminator ends would
     * otherwise open the statement that follows it. Offsets are preserved, so every position this
     * class reports still names the column the script itself writes.
     *
     * <p>The decision is taken for the file, not for the line: a card image is a file most of whose
     * lines reach byte column 73 and hold nothing but digits from there on, and a free-format script
     * that happens to write a numeric literal at the end of a 75-character line is not one. Judging
     * line by line would cut that literal in half.
     */
    private static String withoutSequenceArea(String script, int[] lineStarts,
            IntUnaryOperator byteOffsetOfChar) {
        int[] areaStarts = new int[lineStarts.length];
        int cards = 0;
        int significant = 0;
        for (int line = 0; line < lineStarts.length; line++) {
            int from = lineStarts[line];
            int to = lineEnd(script, lineStarts, line);
            areaStarts[line] = areaStartOf(script, from, to, byteOffsetOfChar);
            if (!script.substring(from, to).isBlank()) {
                significant++;
                cards += areaStarts[line] < 0 ? 0 : 1;
            }
        }
        if (cards == 0 || cards * 2 <= significant) {
            return script;
        }
        StringBuilder out = new StringBuilder(script);
        for (int line = 0; line < lineStarts.length; line++) {
            if (areaStarts[line] >= 0) {
                blank(out, areaStarts[line], lineEnd(script, lineStarts, line));
            }
        }
        return out.toString();
    }

    /**
     * Where a line's identification area starts, or -1 for a line that carries none. The area is the
     * run of digits from byte column 73 to the end of a line that stops by byte column 80; a
     * double-byte character straddling column 72 belongs to the content, because its own column is
     * the one it starts in.
     */
    private static int areaStartOf(String script, int from, int to,
            IntUnaryOperator byteOffsetOfChar) {
        int lineStartByte = byteOffsetOfChar.applyAsInt(from);
        int bytes = byteOffsetOfChar.applyAsInt(to) - lineStartByte;
        if (bytes < FixedFormatColumns.IDENTIFICATION_START || bytes > CARD_BYTES) {
            return -1;
        }
        for (int i = from; i < to; i++) {
            if (byteOffsetOfChar.applyAsInt(i) - lineStartByte + 1
                    >= FixedFormatColumns.IDENTIFICATION_START) {
                return allDigits(script, i, to) ? i : -1;
            }
        }
        return -1;
    }

    /** Where one line's characters stop: at the break that follows it, or at the end of the text. */
    private static int lineEnd(String text, int[] lineStarts, int line) {
        return line + 1 < lineStarts.length
                ? lineBreakAt(text, lineStarts[line + 1]) : text.length();
    }

    private static boolean allDigits(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static void blank(StringBuilder text, int from, int to) {
        for (int i = from; i < to; i++) {
            text.setCharAt(i, ' ');
        }
    }

    /** The offset of the first character of each line, the first line first. */
    private static int[] lineStartsOf(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
                starts.add(i + 1);
            }
        }
        int[] offsets = new int[starts.size()];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = starts.get(i);
        }
        return offsets;
    }

    /** Where the break that precedes the line starting at {@code lineStart} begins. */
    private static int lineBreakAt(String text, int lineStart) {
        return lineStart >= 2 && text.charAt(lineStart - 2) == '\r'
                && text.charAt(lineStart - 1) == '\n' ? lineStart - 2 : lineStart - 1;
    }

    /** The 1-based line an offset falls on. */
    private static int lineOf(int[] lineStarts, int offset) {
        int line = 1;
        while (line < lineStarts.length && lineStarts[line] <= offset) {
            line++;
        }
        return line;
    }

    /** The word starting at {@code at}. */
    private static String wordAt(String text, int at, char terminator) {
        int end = at;
        while (end < text.length() && isNameCharacter(text.charAt(end), terminator)) {
            end++;
        }
        return text.substring(at, end);
    }

    /** Where the next word starts from {@code at}, or -1 when what follows is not one. */
    private static int wordStartAfter(String text, int at) {
        int i = at;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i < text.length() && Character.isLetter(text.charAt(i)) ? i : -1;
    }

    /**
     * What an SQL name may hold, so that END_DATE is one word and not the keyword END. Db2 counts
     * {@code # $ @} as name characters, and a script that ends its statements with {@code @} writes
     * {@code END@} without a space, so the terminator in force is never part of a name.
     */
    private static boolean isNameCharacter(char c, char terminator) {
        return c != terminator
                && (Character.isLetterOrDigit(c) || c == '_' || c == '#' || c == '$' || c == '@');
    }
}
