package jp.cobolinsight.frontend.cobol;

import jp.cobolinsight.core.source.FixedFormatColumns;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A column-aware scan for {@code EXEC SQL ... END-EXEC} blocks in fixed-format COBOL text, and the
 * masking that lets Che4z past a block whose SQL its own grammar will not take.
 *
 * <p>Only columns 8 to 72 count, and a line whose indicator column holds {@code *} or {@code /} is
 * skipped, so a commented-out block is not found and a comment inside a block is not masked.
 * {@code EXEC} and {@code SQL} may stand on different lines, and {@code END-EXEC} may or may not
 * carry a period.</p>
 *
 * <p>{@link #mask} rewrites only the body of the blocks it masks: {@code EXEC SQL} and
 * {@code END-EXEC} stay where they are, every line break and every character outside columns 8 to
 * 72 is kept, and the text keeps its length. Positions therefore mean the same thing in the masked
 * text as in the original, which is what lets the caller read the real SQL back out of the
 * original by range.</p>
 */
final class EmbeddedSqlRanges {

    /** What the first keyword of a block says it is. Only OTHER is ever masked. */
    enum Kind {
        INCLUDE,
        BEGIN_DECLARE_SECTION,
        END_DECLARE_SECTION,
        WHENEVER,
        OTHER
    }

    /**
     * One block.
     *
     * @param kind        what the first keyword makes of it
     * @param startLine   1-based line of the {@code E} of {@code EXEC}
     * @param startColumn 1-based column of the {@code E} of {@code EXEC}
     * @param endLine     1-based line of the last character of {@code END-EXEC}
     * @param endColumn   1-based column of the last character of {@code END-EXEC}
     * @param inProcedureDivision whether the block stands after the PROCEDURE DIVISION header
     * @param body        the character spans between {@code EXEC SQL} and {@code END-EXEC}, as
     *                    half-open offsets into the source text, one span per line
     */
    record Block(Kind kind, int startLine, int startColumn, int endLine, int endColumn,
            boolean inProcedureDivision, List<int[]> body) {

        /** Whether the given 1-based position falls inside this block. */
        boolean contains(int line, int column) {
            if (line < startLine || line > endLine) {
                return false;
            }
            if (line == startLine && column < startColumn) {
                return false;
            }
            return line != endLine || column <= endColumn;
        }
    }

    /**
     * Accepted in the DATA DIVISION; Che4z rejects COMMIT and an empty body there. The shortest
     * DECLARE .. TABLE the Db2 dialect takes, because the body of a one-line block leaves barely
     * more than twenty columns to write it in.
     */
    static final String DATA_PLACEHOLDER = "DECLARE A TABLE(B INT)";

    /** Accepted in the PROCEDURE DIVISION, where a DECLARE .. TABLE is not. */
    static final String PROCEDURE_PLACEHOLDER = "COMMIT";

    private static final Pattern EXEC_SQL =
            Pattern.compile("\\bEXEC\\s+SQL\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_EXEC =
            Pattern.compile("\\bEND-EXEC\\b", Pattern.CASE_INSENSITIVE);
    /**
     * A DIVISION header, and only where one can stand: at the front of a line's code area. The
     * words also occur in a {@code DISPLAY 'PROCEDURE DIVISION'} and inside an SQL body, and
     * neither says anything about which division the line after it belongs to.
     */
    private static final Pattern DIVISION_HEADER = Pattern.compile(
            "^[ \\t]*(IDENTIFICATION|ENVIRONMENT|DATA|PROCEDURE)[ \\t]+DIVISION\\b",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** 0-based offset of the first code column (column 8). */
    private static final int CODE_AREA_START = FixedFormatColumns.AREA_A_START - 1;
    /** 0-based, exclusive end of the code area (just past column 72). */
    private static final int CODE_AREA_END = FixedFormatColumns.CONTENT_END;

    private EmbeddedSqlRanges() {
    }

    /**
     * The blocks of the text. A block runs from its {@code EXEC SQL} to the first {@code END-EXEC}
     * that still stands before the next {@code EXEC SQL}: an {@code END-EXEC} the scan does not see
     * — one broken over a continuation line, say — drops the block rather than stretching it over
     * the COBOL statements that follow, which {@link #mask} would then blank.
     */
    static List<Block> scan(String text) {
        Code code = Code.of(text);
        List<int[]> divisions = divisionHeaders(code);
        List<Block> blocks = new ArrayList<>();
        Matcher exec = EXEC_SQL.matcher(code.text);
        Matcher end = END_EXEC.matcher(code.text);
        int from = 0;
        while (exec.find(from)) {
            int start = exec.start();
            int bodyStart = exec.end();
            int limit = exec.find(bodyStart) ? exec.start() : code.text.length();
            if (end.find(bodyStart) && end.end() <= limit) {
                blocks.add(code.block(start, bodyStart, end.start(), end.end(),
                        inProcedureDivision(divisions, start)));
                from = end.end();
            } else {
                from = bodyStart;
            }
        }
        return blocks;
    }

    /**
     * The source text with the body of every OTHER block replaced by a placeholder Che4z accepts,
     * or null when no block could be masked. A block whose body has no run wide enough for the
     * placeholder is left alone: the retry is worth trying with the rest masked.
     */
    static String mask(String text, List<Block> blocks) {
        char[] out = text.toCharArray();
        boolean masked = false;
        for (Block block : blocks) {
            if (block.kind() != Kind.OTHER) {
                continue;
            }
            String placeholder = block.inProcedureDivision()
                    ? PROCEDURE_PLACEHOLDER : DATA_PLACEHOLDER;
            int[] room = widestSpan(block.body());
            if (room == null || room[1] - room[0] < placeholder.length()) {
                continue;
            }
            for (int[] span : block.body()) {
                for (int i = span[0]; i < span[1]; i++) {
                    out[i] = ' ';
                }
            }
            placeholder.getChars(0, placeholder.length(), out, room[0]);
            masked = true;
        }
        return masked ? new String(out) : null;
    }

    private static int[] widestSpan(List<int[]> body) {
        int[] widest = null;
        for (int[] span : body) {
            if (widest == null || span[1] - span[0] > widest[1] - widest[0]) {
                widest = span;
            }
        }
        return widest;
    }

    /**
     * Every DIVISION header of the text, as {@code {offset, 1 when it opens the PROCEDURE
     * DIVISION}}. A file may hold several programs, so which division a block stands in is decided
     * per block from the nearest header before it, not from the first PROCEDURE DIVISION of the
     * file.
     */
    private static List<int[]> divisionHeaders(Code code) {
        List<int[]> headers = new ArrayList<>();
        Matcher m = DIVISION_HEADER.matcher(code.text);
        while (m.find()) {
            headers.add(new int[] {m.start(),
                    m.group(1).equalsIgnoreCase("PROCEDURE") ? 1 : 0});
        }
        return headers;
    }

    private static boolean inProcedureDivision(List<int[]> headers, int at) {
        boolean inProcedure = false;
        for (int[] header : headers) {
            if (header[0] >= at) {
                break;
            }
            inProcedure = header[1] == 1;
        }
        return inProcedure;
    }

    /**
     * The code areas of every non-comment line, joined by newlines so that {@code EXEC} and
     * {@code SQL} on separate lines still match, with each character mapped back to its offset in
     * the source text.
     */
    private static final class Code {

        private final String text;
        /** Source offset of each character of {@link #text}; -1 for the joining newlines. */
        private final int[] offsets;
        /** 1-based source line of each character of {@link #text}; 0 for the joining newlines. */
        private final int[] lines;
        /** 1-based source column of each character of {@link #text}; 0 for the joining newlines. */
        private final int[] columns;

        private Code(String text, int[] offsets, int[] lines, int[] columns) {
            this.text = text;
            this.offsets = offsets;
            this.lines = lines;
            this.columns = columns;
        }

        static Code of(String source) {
            StringBuilder text = new StringBuilder(source.length());
            List<Integer> offsets = new ArrayList<>(source.length());
            List<Integer> lines = new ArrayList<>(source.length());
            List<Integer> columns = new ArrayList<>(source.length());
            int line = 1;
            int lineStart = 0;
            for (int i = 0; i <= source.length(); i++) {
                boolean atEnd = i == source.length();
                if (!atEnd && source.charAt(i) != '\n') {
                    continue;
                }
                int lineEnd = i;
                if (lineEnd > lineStart && source.charAt(lineEnd - 1) == '\r') {
                    lineEnd--;
                }
                if (!isCommentLine(source, lineStart, lineEnd)) {
                    int from = Math.min(lineStart + CODE_AREA_START, lineEnd);
                    int to = Math.min(lineStart + CODE_AREA_END, lineEnd);
                    for (int j = from; j < to; j++) {
                        text.append(source.charAt(j));
                        offsets.add(j);
                        lines.add(line);
                        columns.add(j - lineStart + 1);
                    }
                }
                text.append('\n');
                offsets.add(-1);
                lines.add(0);
                columns.add(0);
                line++;
                lineStart = i + 1;
                if (atEnd) {
                    break;
                }
            }
            return new Code(text.toString(), toArray(offsets), toArray(lines), toArray(columns));
        }

        private static int[] toArray(List<Integer> values) {
            int[] out = new int[values.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = values.get(i);
            }
            return out;
        }

        private static boolean isCommentLine(String source, int lineStart, int lineEnd) {
            int indicator = lineStart + FixedFormatColumns.INDICATOR_COLUMN - 1;
            if (indicator >= lineEnd) {
                return false;
            }
            char c = source.charAt(indicator);
            return c == '*' || c == '/';
        }

        /** Builds one block from the code-buffer positions of its EXEC SQL and END-EXEC matches. */
        Block block(int execStart, int execEnd, int endStart, int endEnd, boolean inProcedure) {
            return new Block(kindOf(text.substring(execEnd, endStart)),
                    lines[execStart], columns[execStart],
                    lines[endEnd - 1], columns[endEnd - 1],
                    inProcedure, spans(execEnd, endStart));
        }

        /** The source offset spans of the code-buffer range, one per line it covers. */
        private List<int[]> spans(int from, int to) {
            List<int[]> spans = new ArrayList<>();
            int start = -1;
            int previous = -1;
            for (int i = from; i < to; i++) {
                int offset = offsets[i];
                if (offset < 0) {
                    continue;
                }
                if (start < 0 || offset != previous + 1) {
                    if (start >= 0) {
                        spans.add(new int[] {start, previous + 1});
                    }
                    start = offset;
                }
                previous = offset;
            }
            if (start >= 0) {
                spans.add(new int[] {start, previous + 1});
            }
            return spans;
        }
    }

    private static Kind kindOf(String body) {
        String normalized = body.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("INCLUDE ")) {
            return Kind.INCLUDE;
        }
        if (normalized.startsWith("BEGIN DECLARE SECTION")) {
            return Kind.BEGIN_DECLARE_SECTION;
        }
        if (normalized.startsWith("END DECLARE SECTION")) {
            return Kind.END_DECLARE_SECTION;
        }
        if (normalized.startsWith("WHENEVER ")) {
            return Kind.WHENEVER;
        }
        return Kind.OTHER;
    }
}
