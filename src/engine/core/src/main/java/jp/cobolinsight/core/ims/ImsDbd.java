package jp.cobolinsight.core.ims;

import jp.cobolinsight.core.source.SourcePosition;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An IMS database description as its DBDGEN source states it: the database name, its access
 * method and its segments, each with the parent it hangs under.
 */
public record ImsDbd(String name, String access, List<Segment> segments, String sourceFile,
        SourcePosition position) {

    /** One SEGM statement and the names of the FIELD statements under it. */
    public record Segment(String name, Optional<String> parent, Optional<Integer> bytes,
            List<String> fields, SourcePosition position) {

        public Segment {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            Objects.requireNonNull(parent, "parent");
            Objects.requireNonNull(bytes, "bytes");
            fields = List.copyOf(fields);
            Objects.requireNonNull(position, "position");
        }
    }

    public ImsDbd {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(access, "access");
        segments = List.copyOf(segments);
        Objects.requireNonNull(position, "position");
    }

    public boolean hasSegment(String segmentName) {
        return segments.stream().anyMatch(segment -> segment.name().equalsIgnoreCase(segmentName));
    }
}
