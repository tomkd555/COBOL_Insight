package jp.cobolinsight.frontend.ims;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads assembler macro statements: an optional label in column 1, the operation, and operands
 * written as {@code KEYWORD=value} separated by commas. A statement continues on the next line when
 * column 72 is not blank, or when its operand field ends in a comma, and the continuation starts in
 * column 16. A line starting with {@code *} or {@code .*} is a comment.
 */
final class MacroStatements {

    /** One macro call: its label, its operation and its keyword operands, in source order. */
    record Statement(String label, String operation, Map<String, String> operands, int line) {

        /** The operand's value with the parentheses around a list taken off, or null. */
        String value(String keyword) {
            return operands.get(keyword);
        }

        /** The items of an operand written as a list, or the single value as a list of one. */
        List<String> list(String keyword) {
            String value = operands.get(keyword);
            if (value == null) {
                return List.of();
            }
            String inner = value.startsWith("(") && value.endsWith(")")
                    ? value.substring(1, value.length() - 1) : value;
            return splitTopLevel(inner);
        }
    }

    private static final int CONTINUATION_COLUMN = 72;

    private MacroStatements() {
    }

    static List<Statement> read(String text) {
        List<Statement> statements = new ArrayList<>();
        String[] lines = text.split("\\r\\n|\\r|\\n|\\u0085", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            int startLine = i + 1;
            i++;
            if (line.isBlank() || line.startsWith("*") || line.startsWith(".*")) {
                continue;
            }
            String label = "";
            String rest = area(line);
            if (!Character.isWhitespace(rest.charAt(0))) {
                int blank = indexOfBlank(rest);
                label = rest.substring(0, blank);
                rest = rest.substring(blank);
            }
            rest = rest.stripLeading();
            if (rest.isEmpty()) {
                continue;
            }
            int blank = indexOfBlank(rest);
            String operation = rest.substring(0, blank).toUpperCase(Locale.ROOT);
            StringBuilder operands = new StringBuilder(operandField(rest.substring(blank)));
            boolean continued = isContinued(line) || operands.toString().endsWith(",");
            while (continued && i < lines.length) {
                String next = lines[i];
                if (next.startsWith("*") || next.startsWith(".*")) {
                    i++;
                    continue;
                }
                i++;
                String field = operandField(area(next));
                operands.append(field);
                continued = isContinued(next) || field.endsWith(",");
            }
            statements.add(new Statement(label.toUpperCase(Locale.ROOT), operation,
                    keywordOperands(operands.toString()), startLine));
        }
        return statements;
    }

    /** Columns 1 to 71: column 72 is the continuation mark and 73 onwards the sequence number. */
    private static String area(String line) {
        return line.length() >= CONTINUATION_COLUMN
                ? line.substring(0, CONTINUATION_COLUMN - 1) : line;
    }

    private static boolean isContinued(String line) {
        return line.length() >= CONTINUATION_COLUMN
                && !Character.isWhitespace(line.charAt(CONTINUATION_COLUMN - 1));
    }

    private static int indexOfBlank(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return text.length();
    }

    /** The operand field: from the first non-blank up to the first blank outside quotes and parentheses. */
    private static String operandField(String text) {
        String field = text.stripLeading();
        int level = 0;
        boolean quoted = false;
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                level++;
            } else if (!quoted && c == ')') {
                level--;
            } else if (!quoted && level <= 0 && Character.isWhitespace(c)) {
                return field.substring(0, i);
            }
        }
        return field.stripTrailing();
    }

    private static Map<String, String> keywordOperands(String field) {
        Map<String, String> operands = new LinkedHashMap<>();
        for (String operand : splitTopLevel(field)) {
            int equals = operand.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            operands.put(operand.substring(0, equals).strip().toUpperCase(Locale.ROOT),
                    operand.substring(equals + 1).strip().toUpperCase(Locale.ROOT));
        }
        return operands;
    }

    /** Splits at the commas that stand outside parentheses and quotes. */
    static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int level = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                level++;
            } else if (!quoted && c == ')') {
                level--;
            } else if (!quoted && level == 0 && c == ',') {
                parts.add(text.substring(start, i).strip());
                start = i + 1;
            }
        }
        String last = text.substring(start).strip();
        if (!last.isEmpty()) {
            parts.add(last);
        }
        return parts;
    }
}
