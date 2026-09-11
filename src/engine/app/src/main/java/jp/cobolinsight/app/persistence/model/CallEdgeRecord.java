package jp.cobolinsight.app.persistence.model;

/**
 * A row of the CALL_EDGE table (a call-graph edge). resolution, hostVar and line are optional.
 * seq is a 1-based number expressing which of the origin node's outgoing edges this is in the
 * original order; an edge whose order is unknown gets 0. line is the calling line; an edge whose
 * line is unknown gets null.
 *
 * <p>access is how the origin uses the target — a step's use of a data set, a program's use of a
 * Db2 table — pulled out of the edge's attributes because the screens sort and filter on it.
 * attrsJson holds the attributes that have no column of their own, access excluded: keeping it in
 * both places would be two spellings of one fact to keep in step. An edge whose only attribute is
 * access therefore has a null attrsJson.
 */
public record CallEdgeRecord(long id, long fromNode, long toNode, String kind, String resolution,
        String hostVar, int seq, Integer line, String access, String attrsJson) {

    public CallEdgeRecord {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
    }

    /** An edge with no attributes: the shape every caller built before the attributes existed. */
    public CallEdgeRecord(long id, long fromNode, long toNode, String kind, String resolution,
            String hostVar, int seq, Integer line) {
        this(id, fromNode, toNode, kind, resolution, hostVar, seq, line, null, null);
    }

    /** An edge with neither order nor line (e.g. a copybook-inclusion edge, where original order does not matter). */
    public CallEdgeRecord(long id, long fromNode, long toNode, String kind, String resolution,
            String hostVar) {
        this(id, fromNode, toNode, kind, resolution, hostVar, 0, null);
    }
}
