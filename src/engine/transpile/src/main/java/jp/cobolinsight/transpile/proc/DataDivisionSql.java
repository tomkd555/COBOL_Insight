package jp.cobolinsight.transpile.proc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Collects EXEC SQL ... END-EXEC directives from the original source, before PROCEDURE DIVISION
 * (i.e. in the data division). DB2 preprocessor directives such as INCLUDE SQLCA or
 * BEGIN/END DECLARE SECTION in the working-storage section are not carried by the semantic
 * model, so the original source must be scanned directly to render them as untranslatable
 * annotation comments. Lines are 1-based.
 */
public final class DataDivisionSql {

    /** One EXEC SQL directive found in the data division: its line range (1-based, inclusive) and source lines. */
    public record Directive(int startLine, int endLine, List<String> textLines) {
        public Directive {
            textLines = List.copyOf(textLines);
        }
    }

    private DataDivisionSql() {
    }

    public static List<Directive> extract(String sourceText) {
        if (sourceText == null) {
            return List.of();
        }
        String[] lines = sourceText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<Directive> directives = new ArrayList<>();
        List<String> current = null;
        int startLine = 0;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (current == null && isProcedureDivision(upper)) {
                break;
            }
            if (trimmed.startsWith("*")) {
                continue; // Skip comment lines (e.g. EXEC CICS notes in the identification division)
            }
            if (current == null && upper.contains("EXEC SQL")) {
                current = new ArrayList<>();
                startLine = i + 1;
            }
            if (current != null) {
                current.add(trimmed);
                if (upper.contains("END-EXEC")) {
                    directives.add(new Directive(startLine, i + 1, current));
                    current = null;
                }
            }
        }
        return directives;
    }

    private static boolean isProcedureDivision(String upper) {
        return upper.startsWith("PROCEDURE DIVISION")
                || upper.contains(" PROCEDURE DIVISION")
                || upper.equals("PROCEDURE DIVISION.");
    }
}
