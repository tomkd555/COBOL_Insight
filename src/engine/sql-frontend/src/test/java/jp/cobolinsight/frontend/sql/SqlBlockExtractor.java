package jp.cobolinsight.frontend.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test support, not a production path: it is what lets the tests of this module read the SQL of a
 * whole sample without depending on cobol-frontend, which is where production takes its blocks
 * from. Extracts EXEC SQL ... END-EXEC blocks from a fixed-format COBOL source and classifies
 * their kind. Comment lines (column 7 is * or /) are skipped, and only columns 8-72 of each line
 * are considered (columns are counted in characters).
 */
public final class SqlBlockExtractor {

    private static final Pattern EXEC_SQL = Pattern.compile("\\bEXEC\\s+SQL\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_EXEC = Pattern.compile("\\bEND-EXEC\\b", Pattern.CASE_INSENSITIVE);
    /** The 0-based offset just after column 7 (the indicator area), i.e. pointing at column 8. */
    private static final int CODE_AREA_START = 7;
    /** The 0-based, exclusive upper bound for taking text up through column 72. */
    private static final int CODE_AREA_END = 72;

    public List<SqlBlock> extract(String cobolSource) {
        List<SqlBlock> blocks = new ArrayList<>();
        String[] lines = cobolSource.split("\r?\n", -1);
        boolean inBlock = false;
        SourcePosition start = null;
        StringBuilder text = null;
        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx];
            int lineNo = lineIdx + 1;
            if (isCommentLine(line)) {
                continue;
            }
            String code = codeArea(line);
            int pos = 0;
            while (pos < code.length()) {
                if (!inBlock) {
                    Matcher m = EXEC_SQL.matcher(code);
                    if (!m.find(pos)) {
                        break;
                    }
                    inBlock = true;
                    // Columns are kept 1-based, so add +1 to the 0-based position within the code area to get the original source column.
                    start = new SourcePosition(lineNo, CODE_AREA_START + m.start() + 1);
                    text = new StringBuilder();
                    pos = m.end();
                } else {
                    Matcher m = END_EXEC.matcher(code);
                    if (m.find(pos)) {
                        append(text, code.substring(pos, m.start()));
                        // m.end() points just past the match, so without +1 it is already the column of the last character of END-EXEC.
                        SourcePosition end = new SourcePosition(lineNo, CODE_AREA_START + m.end());
                        String sql = text.toString();
                        blocks.add(new SqlBlock(sql, classify(sql), start, end));
                        inBlock = false;
                        pos = m.end();
                    } else {
                        append(text, code.substring(pos));
                        pos = code.length();
                    }
                }
            }
        }
        return blocks;
    }

    private static boolean isCommentLine(String line) {
        if (line.length() < CODE_AREA_START) {
            return false;
        }
        char indicator = line.charAt(CODE_AREA_START - 1);
        return indicator == '*' || indicator == '/';
    }

    private static String codeArea(String line) {
        if (line.length() <= CODE_AREA_START) {
            return "";
        }
        return line.substring(CODE_AREA_START, Math.min(CODE_AREA_END, line.length()));
    }

    private static void append(StringBuilder text, String segment) {
        String trimmed = segment.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(trimmed);
    }

    private static SqlBlockKind classify(String sqlText) {
        String normalized = sqlText.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("INCLUDE ")) {
            return SqlBlockKind.INCLUDE;
        }
        if (normalized.startsWith("BEGIN DECLARE SECTION")) {
            return SqlBlockKind.BEGIN_DECLARE_SECTION;
        }
        if (normalized.startsWith("END DECLARE SECTION")) {
            return SqlBlockKind.END_DECLARE_SECTION;
        }
        if (normalized.startsWith("WHENEVER ")) {
            return SqlBlockKind.WHENEVER;
        }
        if (normalized.startsWith("DECLARE ") && normalized.contains(" CURSOR")) {
            return SqlBlockKind.DECLARE_CURSOR;
        }
        return SqlBlockKind.EXECUTABLE;
    }
}
