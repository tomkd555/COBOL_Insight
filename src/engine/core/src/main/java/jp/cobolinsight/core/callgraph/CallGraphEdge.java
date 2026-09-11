package jp.cobolinsight.core.callgraph;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * An edge of the call graph. References both endpoints by node ID and holds the resolution basis.
 * seq is a 1-based number expressing which of the source node's outgoing edges this is, in the
 * original order (JCL step order, statement appearance order); an edge whose order is unknown uses 0.
 * line is the line of the call site in the source, or null when unknown.
 *
 * <p>Edge identity is determined solely by the two endpoints, kind, and resolution basis. This is so
 * that calling the same target from multiple places collapses into a single edge, with seq and line
 * recorded from the first occurrence. attributes holds kind-specific auxiliary information (the
 * access a step makes of a data set, the plan a Db2 batch program runs under) in ascending key
 * order, and takes no part in identity, for the same reason.
 */
public record CallGraphEdge(String fromId, String toId, EdgeKind kind, Resolution resolution,
        int seq, Integer line, Map<String, String> attributes) {

    public CallGraphEdge {
        if (fromId == null || fromId.isBlank()) {
            throw new IllegalArgumentException("fromId must not be blank");
        }
        if (toId == null || toId.isBlank()) {
            throw new IllegalArgumentException("toId must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(resolution, "resolution");
        attributes = Collections.unmodifiableSortedMap(new TreeMap<>(attributes));
    }

    /** An edge carrying nothing beyond its order and line. */
    public CallGraphEdge(String fromId, String toId, EdgeKind kind, Resolution resolution,
            int seq, Integer line) {
        this(fromId, toId, kind, resolution, seq, line, Map.of());
    }

    /** An edge whose order and line are both unknown. */
    public CallGraphEdge(String fromId, String toId, EdgeKind kind, Resolution resolution) {
        this(fromId, toId, kind, resolution, 0, null);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof CallGraphEdge other
                && fromId.equals(other.fromId)
                && toId.equals(other.toId)
                && kind == other.kind
                && resolution == other.resolution;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromId, toId, kind, resolution);
    }
}
