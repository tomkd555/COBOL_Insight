package jp.cobolinsight.core.rule;

/**
 * The analysis artefacts a rule reads. The pipeline runs a step only when some enabled rule of the
 * command being run declares the artefact that step produces, so a missing entry silently starves
 * the rule of its input.
 *
 * <p>{@link #SEMANTIC} and {@link #BMS} are advisory: parsing is unconditional, so no step is gated
 * on them. They still document what a rule reads and are reported by the {@code rules} subcommand.
 */
public enum Needs {
    /** The COBOL semantic model. Advisory: parsing is unconditional. */
    SEMANTIC,
    /** Control flow graphs. */
    CFG,
    /** Inter-procedural data flow facts. */
    DATAFLOW,
    /** The cross-asset call graph. */
    CALL_GRAPH,
    /** Parsed embedded SQL statements. */
    SQL,
    /** BMS mapsets. Advisory: parsing is unconditional. */
    BMS,
    /** The decoded source text index. */
    SOURCE_TEXT
}
