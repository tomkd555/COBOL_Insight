package jp.cobolinsight.rules.syntax;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lexical helper shared by the syntax rules. Handles COBOL fixed-format comment detection, literal stripping, and name extraction. */
final class CobolTexts {

    /** A COBOL data name or paragraph name (including Japanese names). */
    static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}-]*");

    private static final Pattern STRING_LITERAL = Pattern.compile("'[^']*'|\"[^\"]*\"");

    /** One logical line to scan. A continuation line (column 7 is '-') has already been joined; lineNumber points to the starting line. */
    record LogicalLine(int lineNumber, String text) {
    }

    private CobolTexts() {
    }

    /** Treats a fixed-format line whose column 7 is '*' or '/' as a comment line. */
    static boolean isCommentLine(String line) {
        if (line.length() < 7) {
            return false;
        }
        char indicator = line.charAt(6);
        return indicator == '*' || indicator == '/';
    }

    /**
     * Extracts, from fixed-format source text, the body lines the rules use to scan the
     * original source. Excludes comment lines, blanks out columns 1-7, strips column 73
     * onward (the identification area), and blanks out an inline comment (*>) outside a
     * literal and everything after it. A continuation line (column 7 is '-') appends
     * columns 8-72 to the end of the previous line (the line number keeps the starting
     * line). Blanking out and truncating this way keeps each line's column positions
     * matching the original source's columns.
     */
    static List<LogicalLine> logicalLines(String text) {
        List<LogicalLine> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].replace("\r", "");
            if (isCommentLine(raw)) {
                continue;
            }
            boolean continuation = raw.length() >= 7 && raw.charAt(6) == '-' && !out.isEmpty();
            String body = blankInlineComment(bodyOf(raw));
            if (continuation) {
                LogicalLine previous = out.remove(out.size() - 1);
                String continued = body.length() > 7 ? body.substring(7) : "";
                out.add(new LogicalLine(previous.lineNumber(), previous.text() + continued));
            } else {
                out.add(new LogicalLine(i + 1, body));
            }
        }
        return out;
    }

    /** Blanks out columns 1-7 (the sequence-number and indicator area) and strips column 73 onward (the identification area). */
    private static String bodyOf(String raw) {
        String line = raw.length() > 72 ? raw.substring(0, 72) : raw;
        if (line.length() <= 7) {
            return line;
        }
        return "       " + line.substring(7);
    }

    /** Replaces an inline comment (*>) that appears outside a literal, and everything after it, with blanks of the same length. */
    private static String blankInlineComment(String line) {
        int index = stripLiterals(line).indexOf("*>");
        if (index < 0) {
            return line;
        }
        return line.substring(0, index) + " ".repeat(line.length() - index);
    }

    /**
     * Replaces a string literal with blanks of the same length. This prevents characters
     * inside the literal from being mistaken for a name, while keeping the character
     * position (column) intact.
     */
    static String stripLiterals(String text) {
        Matcher matcher = STRING_LITERAL.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        do {
            out.append(text, last, matcher.start());
            out.append(" ".repeat(matcher.end() - matcher.start()));
            last = matcher.end();
        } while (matcher.find());
        out.append(text, last, text.length());
        return out.toString();
    }

    /** Extracts name tokens (uppercased) from text with literals already stripped. */
    static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher matcher = NAME.matcher(stripLiterals(text));
        while (matcher.find()) {
            out.add(matcher.group().toUpperCase(Locale.ROOT));
        }
        return out;
    }

    static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }
}
