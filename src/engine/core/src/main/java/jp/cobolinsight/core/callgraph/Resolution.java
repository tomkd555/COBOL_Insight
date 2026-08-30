package jp.cobolinsight.core.callgraph;

/** The resolution basis of an edge. */
public enum Resolution {
    /** Determined definitively from a literal or constant. */
    CONSTANT,
    /** Resolved by dataflow analysis: constant propagation, def-use, or value-set. */
    DATAFLOW,
    /** Could not be resolved (the target is an unresolved node). */
    UNRESOLVED
}
