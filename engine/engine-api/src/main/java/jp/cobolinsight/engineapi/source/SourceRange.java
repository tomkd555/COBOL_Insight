package jp.cobolinsight.engineapi.source;

import java.util.Objects;

/** 同一ファイル内の範囲。start と end が同一位置の空範囲を許す。 */
public record SourceRange(SourcePosition start, SourcePosition end) {

    public SourceRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.file().equals(end.file())) {
            throw new IllegalArgumentException(
                    "start and end must be in the same file: " + start.file() + " vs " + end.file());
        }
        if (end.line() < start.line()
                || (end.line() == start.line() && end.column() < start.column())) {
            throw new IllegalArgumentException("end must not precede start: " + start + " -> " + end);
        }
    }
}
