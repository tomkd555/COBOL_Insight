package jp.cobolinsight.analysis.dataflow;

/**
 * One fact from taint tracking. Represents variable acquiring taint at node nodeId; fromVariable
 * and fromNodeId point to the fact that is the direct propagation source of that taint. Holding
 * a node by id rather than identity keeps set equality checks stable, and holding the
 * propagation source as a value of the fact keeps the lattice finite (the path is not nested
 * within it).
 */
record TaintFact(String variable, int nodeId, String fromVariable, int fromNodeId) {

    /** The nodeId for taint originating from a data-division declaration (a sensitive name). There is no corresponding CFG node. */
    static final int DECLARATION = -1;

    /** The fromNodeId denoting that this has no propagation source (it is itself a taint source). */
    static final int NO_SOURCE = -2;

    static TaintFact declared(String variable) {
        return new TaintFact(variable, DECLARATION, null, NO_SOURCE);
    }

    static TaintFact source(String variable, int nodeId) {
        return new TaintFact(variable, nodeId, null, NO_SOURCE);
    }

    static TaintFact propagated(String variable, int nodeId, TaintFact from) {
        return new TaintFact(variable, nodeId, from.variable(), from.nodeId());
    }
}
