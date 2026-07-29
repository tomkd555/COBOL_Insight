package jp.cobolinsight.sqlfrontend;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 埋め込みSQL解析の入口。固定形式COBOLソースから EXEC SQL 〜 END-EXEC ブロックを抽出し
 * 種別を分類する。コメント行(7桁目が * または /)は読み飛ばし、各行の8〜72桁を対象とする(桁は文字単位)。
 */
public final class SqlBlockExtractor {

    private static final Pattern EXEC_SQL = Pattern.compile("\\bEXEC\\s+SQL\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_EXEC = Pattern.compile("\\bEND-EXEC\\b", Pattern.CASE_INSENSITIVE);
    /** 標識領域である7桁目の直後、すなわち8桁目を指す0始まりのオフセット。 */
    private static final int CODE_AREA_START = 7;
    /** 72桁目までを取るための、0始まりで終端を除く上限。 */
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
                    // 桁は1始まりで持つため、コード領域内の0始まり位置へ +1 して元ソースの桁に直す。
                    start = new SourcePosition(lineNo, CODE_AREA_START + m.start() + 1);
                    text = new StringBuilder();
                    pos = m.end();
                } else {
                    Matcher m = END_EXEC.matcher(code);
                    if (m.find(pos)) {
                        append(text, code.substring(pos, m.start()));
                        // m.end() は一致部分の直後を指すので、+1 なしで END-EXEC の末尾文字の桁になる。
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
