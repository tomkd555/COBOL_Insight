package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text scanning shared by R034 (a MOVE into a numeric item with an untested alphanumeric sender)
 * and R049 (a FILE/LINKAGE USAGE DISPLAY numeric item used in an arithmetic statement or a numeric
 * relation with no NUMERIC test). Both rules ask the same question of a data name — does any
 * {@code <name> [IS] [NOT] NUMERIC} condition test it anywhere in the program — so the whole-source
 * scan for that condition, and the small text helpers around it, live here once.
 */
final class NumericClassSupport {

    private static final String NAME_CHARS = "\\p{L}\\p{N}$#_-";
    static final Pattern NAME_TOKEN =
            Pattern.compile("[" + NAME_CHARS + "]*\\p{L}[" + NAME_CHARS + "]*");
    private static final Pattern NUMERIC_TEST = Pattern.compile("(?i)([" + NAME_CHARS
            + "]*\\p{L}[" + NAME_CHARS + "]*)\\s+(?:IS\\s+)?(?:NOT\\s+)?NUMERIC\\b");

    private NumericClassSupport() {
    }

    /** The normalized names some {@code <name> [IS] [NOT] NUMERIC} condition tests anywhere in programText. */
    static Set<String> numericTestedNames(String programText) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = NUMERIC_TEST.matcher(maskLiterals(programText));
        while (m.find()) {
            names.add(norm(m.group(1)));
        }
        return names;
    }

    static String norm(String name) {
        return name.trim().toUpperCase(Locale.ROOT);
    }

    static int indexOfWord(String upper, String word) {
        Matcher m = Pattern.compile("(?<![" + NAME_CHARS + "])" + word + "(?![" + NAME_CHARS + "])")
                .matcher(upper);
        return m.find() ? m.start() : -1;
    }

    /** Pre-order traversal, in definition order, of statements and the nested bodies of their compound statements. */
    static void walk(List<Statement> statements, Consumer<Statement> visitor) {
        for (Statement statement : statements) {
            visitor.accept(statement);
            if (statement instanceof CompoundStatement compound) {
                for (StatementBlock block : compound.blocks()) {
                    walk(block.statements(), visitor);
                }
            }
        }
    }

    /**
     * Replaces a subscript or reference modification enclosed in parentheses with spaces, so a
     * table index or a reference-modification bound is not mistaken for an operand name.
     */
    static String maskParenthesized(String region) {
        StringBuilder sb = new StringBuilder(region);
        int depth = 0;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                continue;
            }
            sb.setCharAt(i, ' ');
        }
        return sb.toString();
    }

    static String maskLiterals(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                sb.append(' ');
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
