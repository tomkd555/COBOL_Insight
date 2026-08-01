package jp.cobolinsight.transpile.proc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * データ部(PROCEDURE DIVISION より前)の EXEC SQL ... END-EXEC 指令を原ソースから拾う。作業部の
 * INCLUDE SQLCA・BEGIN/END DECLARE SECTION 等の DB2 プリプロセッサ指令は意味モデルに載らないため、
 * 直訳不能として注記コメント化するには原ソースを直接走査する必要がある。行は1始まり。
 */
public final class DataDivisionSql {

    /** データ部で見つかった1件の EXEC SQL 指令。行範囲(1始まり・両端含む)と原文行を持つ。 */
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
                continue; // コメント行(識別部の EXEC CICS 注記など)は対象外
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
