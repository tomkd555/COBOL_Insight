package jp.cobolinsight.app.persistence.model;

/**
 * A row of the CALL_EDGE table (a call-graph edge). resolution, hostVar and line are optional.
 * seq is a 1-based number expressing which of the origin node's outgoing edges this is in the
 * original order; an edge whose order is unknown gets 0. line is the calling line; an edge whose
 * line is unknown gets null.
 */
public record CallEdgeRecord(long id, long fromNode, long toNode, String kind, String resolution,
        String hostVar, int seq, Integer line) {

    public CallEdgeRecord {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
    }

    /** An edge with neither order nor line (e.g. a copybook-inclusion edge, where original order does not matter). */
    public CallEdgeRecord(long id, long fromNode, long toNode, String kind, String resolution,
            String hostVar) {
        this(id, fromNode, toNode, kind, resolution, hostVar, 0, null);
    }
}
