package jp.cobolinsight.core.cfg;

import jp.cobolinsight.core.semantic.Statement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The control flow graph of a single program. Nodes are kept in generation order (the definition
 * order in the semantic model), successor lists preserve edge-addition order, and the same input
 * always produces the same graph (determinism). The statement-to-node index is looked up by the
 * identity of the semantic model's statement instances.
 */
public final class ControlFlowGraph {

    private final String programId;
    private final List<CfgNode> nodes;
    private final CfgNode entry;
    private final CfgNode exit;
    private final Map<CfgNode, List<CfgNode>> successors;
    private final Map<CfgNode, List<CfgNode>> predecessors;
    private final Map<Statement, CfgNode> nodeByStatement;

    public ControlFlowGraph(String programId, List<CfgNode> nodes, CfgNode entry, CfgNode exit,
            Map<CfgNode, List<CfgNode>> successors, Map<Statement, CfgNode> nodeByStatement) {
        this.programId = Objects.requireNonNull(programId, "programId");
        this.nodes = List.copyOf(nodes);
        this.entry = Objects.requireNonNull(entry, "entry");
        this.exit = Objects.requireNonNull(exit, "exit");
        Map<CfgNode, List<CfgNode>> succ = new LinkedHashMap<>();
        Map<CfgNode, List<CfgNode>> pred = new LinkedHashMap<>();
        for (CfgNode node : this.nodes) {
            succ.put(node, List.copyOf(successors.getOrDefault(node, List.of())));
            pred.put(node, new ArrayList<>());
        }
        for (CfgNode from : this.nodes) {
            for (CfgNode to : succ.get(from)) {
                pred.get(to).add(from);
            }
        }
        pred.replaceAll((node, list) -> List.copyOf(list));
        this.successors = Collections.unmodifiableMap(succ);
        this.predecessors = Collections.unmodifiableMap(pred);
        Map<Statement, CfgNode> byStatement = new IdentityHashMap<>(nodeByStatement);
        this.nodeByStatement = Collections.unmodifiableMap(byStatement);
    }

    public String programId() {
        return programId;
    }

    /** All nodes (generation order). */
    public List<CfgNode> nodes() {
        return nodes;
    }

    public CfgNode entry() {
        return entry;
    }

    public CfgNode exit() {
        return exit;
    }

    /** Direct successors (edge-addition order). */
    public List<CfgNode> successors(CfgNode node) {
        return successors.getOrDefault(node, List.of());
    }

    /** Direct predecessors (node generation order). */
    public List<CfgNode> predecessors(CfgNode node) {
        return predecessors.getOrDefault(node, List.of());
    }

    /** The node corresponding to a semantic-model statement (same instance). */
    public Optional<CfgNode> nodeOf(Statement statement) {
        return Optional.ofNullable(nodeByStatement.get(statement));
    }

    /** Total number of edges. */
    public int edgeCount() {
        return successors.values().stream().mapToInt(List::size).sum();
    }

    /**
     * The set of nodes reachable by simply following successor edges from the entry. Follows every
     * edge kind (including PERFORM return edges and paragraph fall-through edges). Iteration order is
     * deterministic to preserve reachability order. Used as the basis for unreachable-code detection
     * (R011).
     */
    public Set<CfgNode> reachableNodes() {
        Set<CfgNode> visited = new LinkedHashSet<>();
        Deque<CfgNode> queue = new ArrayDeque<>();
        visited.add(entry);
        queue.addLast(entry);
        while (!queue.isEmpty()) {
            CfgNode node = queue.removeFirst();
            for (CfgNode succ : successors(node)) {
                if (visited.add(succ)) {
                    queue.addLast(succ);
                }
            }
        }
        return Collections.unmodifiableSet(visited);
    }
}
