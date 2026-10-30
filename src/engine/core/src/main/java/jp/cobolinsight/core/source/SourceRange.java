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

    /**
     * Whether {@code inner} lies inside this range: the same file, starting at or after this
     * range's start and ending at or before its end.
     */
    public boolean contains(SourceRange inner) {
        return start.file().equals(inner.start().file())
                && compare(inner.start(), start) >= 0 && compare(inner.end(), end) <= 0;
    }

    private static int compare(SourcePosition a, SourcePosition b) {
        return a.line() != b.line() ? Integer.compare(a.line(), b.line())
                : Integer.compare(a.column(), b.column());
    }
}
