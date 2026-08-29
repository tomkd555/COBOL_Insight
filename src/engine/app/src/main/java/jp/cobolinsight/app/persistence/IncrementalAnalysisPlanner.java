package jp.cobolinsight.app.persistence;

import jp.cobolinsight.app.persistence.model.CallEdgeRecord;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Determines the set of source IDs that need reanalysis by comparing source content hashes.
 * The dependency scope is limited to two kinds: "the program that imports this copybook" and
 * "the JCL that calls this program." Each target source's node is assumed to be registered as
 * exactly one entry under the NODE.id = SOURCE.id convention, and the two dependency kinds are
 * distinguished by the CALL_EDGE.kind value.
 */
public final class IncrementalAnalysisPlanner {

    /** The CALL_EDGE.kind value representing a copybook -> importing-program dependency. */
    public static final String COPY_EDGE_KIND = "COPY";
    /** The CALL_EDGE.kind value representing a calling-JCL -> program execution dependency. */
    public static final String EXECUTION_EDGE_KIND = "EXECUTION";

    private final PersistenceDao dao;

    public IncrementalAnalysisPlanner(PersistenceDao dao) {
        this.dao = dao;
    }

    /** An empty set means no reanalysis is needed. Otherwise it includes the changed source itself plus its direct dependents and dependencies. */
    public Set<Long> determineReanalysisTargets(long sourceId, String newContentHash) {
        var existing = dao.findSource(sourceId);
        if (existing.isPresent() && existing.get().contentHash().equals(newContentHash)) {
            return Set.of();
        }

        Set<Long> targets = new LinkedHashSet<>();
        targets.add(sourceId);
        targets.addAll(dependentsOf(sourceId));
        return targets;
    }

    /**
     * The other sources that need to be reanalyzed as a result of a change or deletion of this
     * source. Does not include this source itself. A source that has disappeared from the asset
     * folder has no content hash to compare, so propagating a deletion is handled through this
     * query instead.
     */
    public Set<Long> dependentsOf(long sourceId) {
        Set<Long> dependents = new LinkedHashSet<>();
        for (CallEdgeRecord edge : dao.findEdgesFrom(sourceId)) {
            if (COPY_EDGE_KIND.equals(edge.kind())) {
                dependents.add(edge.toNode());
            }
        }
        for (CallEdgeRecord edge : dao.findEdgesTo(sourceId)) {
            if (EXECUTION_EDGE_KIND.equals(edge.kind())) {
                dependents.add(edge.fromNode());
            }
        }
        return dependents;
    }
}
