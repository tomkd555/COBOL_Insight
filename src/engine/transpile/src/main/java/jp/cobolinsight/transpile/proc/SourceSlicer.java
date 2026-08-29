package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts a line range from the original source text. Used to recover details not carried in the
 * semantic model (such as the VARYING/FROM/BY clauses of an inline PERFORM VARYING) from a range.
 * Lines are 1-based.
 */
public final class SourceSlicer {

    private final String[] lines;

    public SourceSlicer(String sourceText) {
        this.lines = sourceText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    /** Joins the start line through the end line of range with single spaces (ignores columns and takes whole lines). */
    public String linesOf(SourceRange range) {
        int start = range.start().line();
        int end = range.end().line();
        StringBuilder sb = new StringBuilder();
        for (int line = start; line <= end && line <= lines.length; line++) {
            if (line >= 1) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(lines[line - 1].trim());
            }
        }
        return sb.toString();
    }

    /**
     * Extracts text from the start position (column inclusive) to the end position (column exclusive).
     * When the range spans multiple lines, each line's leading/trailing whitespace is trimmed and the
     * lines are joined with single spaces. Lines and columns are 1-based; columns are counted in
     * characters of the decoded text.
     */
    public String between(SourcePosition start, SourcePosition end) {
        StringBuilder sb = new StringBuilder();
        for (int line = Math.max(1, start.line()); line <= end.line() && line <= lines.length;
                line++) {
            String text = lines[line - 1];
            int from = line == start.line() ? Math.min(start.column() - 1, text.length()) : 0;
            int to = line == end.line() ? Math.min(end.column() - 1, text.length()) : text.length();
            if (to <= from) {
                continue;
            }
            String part = text.substring(from, to).trim();
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    /** Returns each line of range as-is with leading/trailing whitespace trimmed (used to preserve the original text as comments for untranslatable blocks). */
    public List<String> rawLinesOf(SourceRange range) {
        List<String> result = new ArrayList<>();
        int start = range.start().line();
        int end = range.end().line();
        for (int line = start; line <= end && line <= lines.length; line++) {
            if (line >= 1) {
                result.add(lines[line - 1].trim());
            }
        }
        return result;
    }
}
