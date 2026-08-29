package jp.cobolinsight.core.rule;

/**
 * The analysis artefacts a rule reads. The pipeline runs a step only when some enabled rule of the
 * command being run declares the artefact that step produces, so a missing entry silently starves
 * the rule of its input.
 */
public enum Needs {
    /** The COBOL semantic model. */
    SEMANTIC,
    /** Control flow graphs. */
    CFG,
    /** Inter-procedural data flow facts. */
    DATAFLOW,
    /** The cross-asset call graph. */
    CALL_GRAPH,
    /** Parsed embedded SQL statements. */
    SQL,
    /** BMS mapsets. */
    BMS,
    /** The decoded source text index. */
    SOURCE_TEXT
}
