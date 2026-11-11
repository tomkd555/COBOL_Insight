package jp.cobolinsight.frontend.easytrieve;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Reads Easytrieve source one statement at a time.
 *
 * <p>The statement area is columns 1 to 72. A line whose first non-blank character is {@code *} is
 * a comment. A statement ends with its line unless the last non-blank character of the line is
 * {@code +} or {@code -}, which continues it on the next line; a period followed by a blank ends a
 * statement inside a line, so one line may carry several, and what follows such a period is a
 * comment when it starts with {@code *}.
 *
 * <p>Words are separated by blanks and commas. A literal in apostrophes is one word, apostrophes
 * kept; a parenthesis and an operator are words of their own. Every other word is upper-cased,
 * which is how the language itself reads names and keywords.
 */
final class StatementReader {

    /** One statement: its words, the text it was written as, and the lines it spans. */
    record Raw(List<String> words, String text, String file, int line, int endLine) {
    }

    private static final int STATEMENT_AREA_END = 72;

    private static final String COMPARISON_CHARACTERS = "=<>¬^!";

    private final List<EasytrieveSourceParser.SourceLine> lines;
    private int index;
    private final Deque<Raw> pending = new ArrayDeque<>();

    private final List<String> words = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private String startFile;
    private int startLine;
    private int lastLine;

    StatementReader(List<EasytrieveSourceParser.SourceLine> lines) {
        this.lines = lines;
    }

    /** The next statement, or null when the source is exhausted. */
    Raw next() {
        while (pending.isEmpty() && index < lines.size()) {
            readLine(lines.get(index++));
        }
        if (pending.isEmpty() && !words.isEmpty()) {
            // A continuation mark on the last line of the source: what was gathered still counts.
            flushStatement();
        }
        return pending.pollFirst();
    }

    /** The statement {@link #next()} would return, left in place. */
    Raw peek() {
        Raw raw = next();
        if (raw != null) {
            pending.addFirst(raw);
        }
        return raw;
    }

    /**
     * The physical lines up to, and not including, the first whose first word is {@code terminator};
     * that line is consumed. This is how data that is not statements is stepped over: the rows of an
     * in-stream table up to ENDTABLE, the body of an in-stream macro up to MEND.
     */
    List<EasytrieveSourceParser.SourceLine> linesUntil(String terminator) {
        List<EasytrieveSourceParser.SourceLine> taken = new ArrayList<>();
        while (index < lines.size()) {
            EasytrieveSourceParser.SourceLine line = lines.get(index++);
            String first = area(line.text()).strip();
            int blank = first.indexOf(' ');
            String word = (blank < 0 ? first : first.substring(0, blank)).toUpperCase(Locale.ROOT);
            if (word.endsWith(".")) {
                word = word.substring(0, word.length() - 1);
            }
            if (word.equals(terminator)) {
                break;
            }
            taken.add(line);
        }
        return taken;
    }

    private static String area(String line) {
        return line.length() > STATEMENT_AREA_END ? line.substring(0, STATEMENT_AREA_END) : line;
    }

    private void readLine(EasytrieveSourceParser.SourceLine source) {
        String line = area(source.text()).stripTrailing();
        String body = line.stripLeading();
        if (body.isEmpty()) {
            if (words.isEmpty()) {
                return;
            }
            // A blank line does not continue a statement: the mark stood on the line before it.
            flushStatement();
            return;
        }
        if (body.charAt(0) == '*') {
            return;
        }
        boolean continued = false;
        char last = line.charAt(line.length() - 1);
        if ((last == '+' || last == '-')
                && (line.length() == 1 || Character.isWhitespace(line.charAt(line.length() - 2)))) {
            continued = true;
            line = line.substring(0, line.length() - 1).stripTrailing();
        }
        if (words.isEmpty() && text.length() == 0) {
            startFile = source.file();
            startLine = source.line();
        }
        lastLine = source.line();
        scan(line, source);
        if (!continued) {
            flushStatement();
        }
    }

    private void scan(String line, EasytrieveSourceParser.SourceLine source) {
        StringBuilder word = new StringBuilder();
        int segmentStart = 0;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\'') {
                int close = closingApostrophe(line, i);
                word.append(line, i, close + 1);
                i = close + 1;
                continue;
            }
            if (Character.isWhitespace(c) || c == ',') {
                flushWord(word);
                i++;
                continue;
            }
            if (c == '(' || c == ')') {
                flushWord(word);
                words.add(String.valueOf(c));
                i++;
                continue;
            }
            if (c == '.' && (i + 1 == line.length() || Character.isWhitespace(line.charAt(i + 1)))) {
                flushWord(word);
                appendText(line.substring(segmentStart, i));
                flushStatement();
                String rest = line.substring(i + 1).stripLeading();
                if (rest.isEmpty() || rest.charAt(0) == '*') {
                    return;
                }
                segmentStart = i + 1;
                startFile = source.file();
                startLine = source.line();
                lastLine = source.line();
                i++;
                continue;
            }
            if (COMPARISON_CHARACTERS.indexOf(c) >= 0) {
                flushWord(word);
                int end = i;
                while (end < line.length() && COMPARISON_CHARACTERS.indexOf(line.charAt(end)) >= 0) {
                    end++;
                }
                words.add(line.substring(i, end));
                i = end;
                continue;
            }
            if (c == '*' || c == '/') {
                flushWord(word);
                words.add(String.valueOf(c));
                i++;
                continue;
            }
            if (c == '+' || c == '-') {
                boolean digitNext = i + 1 < line.length() && Character.isDigit(line.charAt(i + 1));
                boolean wordNext = i + 1 < line.length() && isWordCharacter(line.charAt(i + 1));
                if (word.length() > 0 && c == '-' && wordNext) {
                    // FIELD-NAME: the hyphen belongs to the name.
                    word.append(c);
                } else if (word.length() == 0 && digitNext) {
                    // A signed number.
                    word.append(c);
                } else {
                    flushWord(word);
                    words.add(String.valueOf(c));
                }
                i++;
                continue;
            }
            word.append(c);
            i++;
        }
        flushWord(word);
        appendText(line.substring(Math.min(segmentStart, line.length())));
    }

    private static boolean isWordCharacter(char c) {
        return !Character.isWhitespace(c) && c != ',' && c != '(' && c != ')' && c != '\'';
    }

    /** The index of the apostrophe that closes the literal opened at {@code open}; a doubled one is data. */
    private static int closingApostrophe(String line, int open) {
        int i = open + 1;
        while (i < line.length()) {
            if (line.charAt(i) == '\'') {
                if (i + 1 < line.length() && line.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i;
            }
            i++;
        }
        // Left open on this line: the rest of the line is the literal.
        return line.length() - 1;
    }

    private void flushWord(StringBuilder word) {
        if (word.length() == 0) {
            return;
        }
        String value = word.toString();
        boolean literal = value.indexOf('\'') >= 0;
        words.add(literal ? value : value.toUpperCase(Locale.ROOT));
        word.setLength(0);
    }

    private void appendText(String segment) {
        String trimmed = segment.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        if (text.length() > 0) {
            text.append(' ');
        }
        text.append(trimmed);
    }

    private void flushStatement() {
        if (!words.isEmpty()) {
            pending.addLast(new Raw(List.copyOf(words), text.toString(), startFile, startLine,
                    Math.max(startLine, lastLine)));
        }
        words.clear();
        text.setLength(0);
    }
}
