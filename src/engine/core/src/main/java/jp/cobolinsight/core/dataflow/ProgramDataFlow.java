package jp.cobolinsight.core.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Fixed-point analysis result for a single program. Queries are keyed by {@link CfgNode} identity
 * (callers must pass the same CFG instance that produced the result). Variable names are matched
 * after normalization (uppercasing).
 *
 * <p>Produced by the dataflow module and consumed by rules. Since rules does not depend on
 * dataflow, the contract type connecting the two lives in engine-api, which both depend on.
 * {@link jp.cobolinsight.core.cfg.ControlFlowGraphs} follows the same placement.
 */
public interface ProgramDataFlow {

    /** PROGRAM-ID of the target program. */
    String programId();

    /**
     * Whether a WORKING-STORAGE / LINKAGE item without a VALUE clause can reach the entry of
     * useNode still uninitialized (R001). Determined by whether the synthetic "uninitialized
     * definition" seeded at the entry by reaching-definitions analysis reaches there.
     */
    boolean mayReachUninitialized(CfgNode useNode, String varName);

    /** Integer interval for varName at the node's entry (R005/R028). Empty if not tracked. */
    Optional<ValueInterval> intervalAt(CfgNode node, String varName);

    /** Variables tainted of the given kind at the node's entry (R020=EXTERNAL_INPUT, R027=SENSITIVE). */
    Set<String> taintedAt(CfgNode node, TaintKind kind);

    /**
     * For a varName tainted at the node's entry, the sequence of nodes (taint source first) at
     * which the taint was acquired, from the taint source up to that node's entry. Does not
     * include the queried node itself. Empty if not tainted.
     *
     * <p>Because this is a may-analysis, multiple paths can exist. This returns the single
     * shortest path rooted at a taint source, not an exhaustive set of paths. A SENSITIVE taint
     * source is a data-division declaration, not a statement, so the path starts from the first
     * assignment to the declared item.
     */
    List<TaintStep> taintPathTo(CfgNode node, String varName, TaintKind kind);

    /** Variables defined (assigned) at this node. */
    Set<String> defsAt(CfgNode node);

    /** Variables referenced at this node. */
    Set<String> usesAt(CfgNode node);

    /** Variables live at the node's exit. */
    Set<String> liveOut(CfgNode node);
}
