package jp.cobolinsight.core.source;

import java.util.Objects;

/** A range within a single file. An empty range where start and end are the same position is allowed. */
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
