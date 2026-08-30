package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A generic worklist solver for a may analysis (join = union) over a finite lattice, run with a
 * swappable direction and transfer function. Switches between forward (join = predecessor
 * outflow) and backward (join = successor outflow). For a transfer function monotone with
 * respect to the join, the outflow set is non-decreasing on each iteration and, being a finite
 * lattice, always converges to a fixed point.
 *
 * <p>The result is each node's "inflow" set (the value entering the transfer function in the
 * direction of flow). This is the value at a node's entry for a forward analysis, and at a
 * node's exit for a backward analysis. Nodes are indexed by identity ({@code IdentityHashMap})
 * and seeded in {@link ControlFlowGraph#nodes()} order, so convergence is deterministic.
 */
final class WorklistSolver {

    enum Direction {
        FORWARD, BACKWARD
    }

    /** A transfer function that computes a node's outflow set from its inflow set. Must be monotone with respect to the join. */
    interface Transfer<T> {
        Set<T> apply(CfgNode node, Set<T> input);
    }

    private WorklistSolver() {
    }

    /**
     * Computes the fixed point and returns each node's inflow set.
     *
     * @param boundary the initial set injected into the boundary node's inflow (entry for forward, exit for backward)
     */
    static <T> Map<CfgNode, Set<T>> solve(ControlFlowGraph cfg, Direction direction,
            Set<T> boundary, Transfer<T> transfer) {
        Map<CfgNode, Set<T>> flowIn = new IdentityHashMap<>();
        Map<CfgNode, Set<T>> flowOut = new IdentityHashMap<>();
        for (CfgNode node : cfg.nodes()) {
            flowIn.put(node, new LinkedHashSet<>());
            flowOut.put(node, new LinkedHashSet<>());
        }
        CfgNode boundaryNode = direction == Direction.FORWARD ? cfg.entry() : cfg.exit();

        Deque<CfgNode> work = new ArrayDeque<>(cfg.nodes());
        Set<CfgNode> queued = new LinkedHashSet<>(cfg.nodes());
        while (!work.isEmpty()) {
            CfgNode node = work.pollFirst();
            queued.remove(node);

            Set<T> newIn = new LinkedHashSet<>();
            if (node == boundaryNode) {
                newIn.addAll(boundary);
            }
            List<CfgNode> upstream = direction == Direction.FORWARD
                    ? cfg.predecessors(node)
                    : cfg.successors(node);
            for (CfgNode neighbor : upstream) {
                newIn.addAll(flowOut.get(neighbor));
            }
            flowIn.put(node, newIn);

            Set<T> newOut = transfer.apply(node, newIn);
            if (!newOut.equals(flowOut.get(node))) {
                flowOut.put(node, newOut);
                List<CfgNode> downstream = direction == Direction.FORWARD
                        ? cfg.successors(node)
                        : cfg.predecessors(node);
                for (CfgNode dependent : downstream) {
                    if (queued.add(dependent)) {
                        work.addLast(dependent);
                    }
                }
            }
        }
        return flowIn;
    }
}
