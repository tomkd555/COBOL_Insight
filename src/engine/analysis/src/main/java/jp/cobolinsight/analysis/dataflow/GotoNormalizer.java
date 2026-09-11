package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Normalizes a control flow graph containing GO TO into a structurable (reducible) form.
 * Reducibility is decided by identifying back edges from the dominator tree (the Cooper-Harvey-
 * Kennedy iterative algorithm); if reducible, the equivalent graph is returned as-is. An
 * irreducible strongly connected region is made single-entry by controlled node duplication
 * (Hendren, DOI:10.1109/ICCL.1994.288377), duplicating the region for each inflow path. A
 * duplicated node keeps the original node's Statement, procedureName, and source coordinates,
 * and originalNodeId lets you trace back to the node it was duplicated from.
 */
public final class GotoNormalizer {

    private GotoNormalizer() {
    }

    /** The normalized control flow graph. If reducible, the input is returned as-is. */
    public static ControlFlowGraph normalize(ControlFlowGraph cfg) {
        ControlFlowGraph current = cfg;
        int guard = 0;
        int nodeCount = current.nodes().size();
        // The iteration cap is a safeguard against runaway loops. Each iteration makes one
        // irreducible region single-entry, so this normally finishes in a few rounds; hitting
        // the cap means the region identification has a defect.
        int maxIterations = nodeCount * nodeCount + 8;
        while (!isReducible(current)) {
            ControlFlowGraph split = splitFirstIrreducibleRegion(current);
            if (split == current) {
                break;
            }
            current = split;
            if (++guard > maxIterations) {
                throw new IllegalStateException("GO TO正規化が収束しない: " + cfg.programId());
            }
        }
        return current;
    }

    /** True if the subgraph reachable from the entry is reducible (structurable). */
    public static boolean isReducible(ControlFlowGraph cfg) {
        List<CfgNode> reachable = reversePostorder(cfg);
        Map<CfgNode, CfgNode> idom = dominators(cfg, reachable);
        Set<CfgNode> reachableSet = Collections.newSetFromMap(new IdentityHashMap<>());
        reachableSet.addAll(reachable);
        // Reducible if the set of forward edges, excluding back edges (edges where the head dominates the tail), is acyclic.
        Map<CfgNode, List<CfgNode>> forward = new IdentityHashMap<>();
        Map<CfgNode, Integer> indegree = new IdentityHashMap<>();
        for (CfgNode node : reachable) {
            forward.put(node, new ArrayList<>());
            indegree.put(node, 0);
        }
        for (CfgNode from : reachable) {
            for (CfgNode to : cfg.successors(from)) {
                if (!reachableSet.contains(to) || dominates(to, from, idom)) {
                    continue;
                }
                forward.get(from).add(to);
                indegree.merge(to, 1, Integer::sum);
            }
        }
        Deque<CfgNode> queue = new ArrayDeque<>();
        for (CfgNode node : reachable) {
            if (indegree.get(node) == 0) {
                queue.addLast(node);
            }
        }
        int settled = 0;
        while (!queue.isEmpty()) {
            CfgNode node = queue.removeFirst();
            settled++;
            for (CfgNode next : forward.get(node)) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.addLast(next);
                }
            }
        }
        return settled == reachable.size();
    }

    // --- Dominator tree ---

    private static Map<CfgNode, CfgNode> dominators(ControlFlowGraph cfg, List<CfgNode> reversePostorder) {
        Map<CfgNode, Integer> postNumber = new IdentityHashMap<>();
        int n = reversePostorder.size();
        for (int i = 0; i < n; i++) {
            // reversePostorder.get(0) is the entry. Postorder numbers are assigned so the entry gets the maximum.
            postNumber.put(reversePostorder.get(i), n - 1 - i);
        }
        Set<CfgNode> reachableSet = Collections.newSetFromMap(new IdentityHashMap<>());
        reachableSet.addAll(reversePostorder);
        CfgNode entry = cfg.entry();
        Map<CfgNode, CfgNode> idom = new IdentityHashMap<>();
        idom.put(entry, entry);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (CfgNode b : reversePostorder) {
                if (b == entry) {
                    continue;
                }
                CfgNode newIdom = null;
                for (CfgNode p : cfg.predecessors(b)) {
                    if (!reachableSet.contains(p) || !idom.containsKey(p)) {
                        continue;
                    }
                    newIdom = (newIdom == null) ? p : intersect(p, newIdom, idom, postNumber);
                }
                if (newIdom != null && idom.get(b) != newIdom) {
                    idom.put(b, newIdom);
                    changed = true;
                }
            }
        }
        return idom;
    }

    /** Nearest common ancestor on the dominator tree. Walks the side with the smaller postorder number up toward its parent until the two meet. */
    private static CfgNode intersect(CfgNode a, CfgNode b, Map<CfgNode, CfgNode> idom,
            Map<CfgNode, Integer> postNumber) {
        CfgNode f1 = a;
        CfgNode f2 = b;
        while (f1 != f2) {
            while (postNumber.get(f1) < postNumber.get(f2)) {
                f1 = idom.get(f1);
            }
            while (postNumber.get(f2) < postNumber.get(f1)) {
                f2 = idom.get(f2);
            }
        }
        return f1;
    }

    private static boolean dominates(CfgNode dominator, CfgNode node, Map<CfgNode, CfgNode> idom) {
        CfgNode cur = node;
        while (cur != null) {
            if (cur == dominator) {
                return true;
            }
            CfgNode next = idom.get(cur);
            if (next == cur) {
                return false;
            }
            cur = next;
        }
        return false;
    }

    /** Returns the nodes reachable from the entry in reverse postorder (entry first). */
    private static List<CfgNode> reversePostorder(ControlFlowGraph cfg) {
        List<CfgNode> postorder = new ArrayList<>();
        Set<CfgNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<CfgNode> stack = new ArrayDeque<>();
        Deque<Iterator<CfgNode>> iterators = new ArrayDeque<>();
        CfgNode entry = cfg.entry();
        visited.add(entry);
        stack.push(entry);
        iterators.push(cfg.successors(entry).iterator());
        while (!stack.isEmpty()) {
            Iterator<CfgNode> it = iterators.peek();
            if (it.hasNext()) {
                CfgNode next = it.next();
                if (visited.add(next)) {
                    stack.push(next);
                    iterators.push(cfg.successors(next).iterator());
                }
            } else {
                postorder.add(stack.pop());
                iterators.pop();
            }
        }
        Collections.reverse(postorder);
        return postorder;
    }

    // --- Making irreducible regions single-entry by duplication ---

    /** A region and its entry points (irreducible if there is more than one). */
    private record IrreducibleRegion(Set<CfgNode> nodes, List<CfgNode> entries) {
    }

    private static ControlFlowGraph splitFirstIrreducibleRegion(ControlFlowGraph cfg) {
        List<CfgNode> reachable = reversePostorder(cfg);
        Set<CfgNode> allowed = Collections.newSetFromMap(new IdentityHashMap<>());
        allowed.addAll(reachable);
        IrreducibleRegion region = findIrreducibleRegion(cfg, allowed, reachable);
        if (region == null) {
            return cfg;
        }
        return duplicateRegion(cfg, region.nodes(), region.entries());
    }

    /**
     * Examines the strongly connected components of the allowed subgraph and returns a region
     * with multiple entry points (i.e. irreducible). A natural loop with a single entry point
     * recurses into its body with the header removed, so nested irreducible loops are also
     * detected. The traversal order is kept deterministic.
     */
    private static IrreducibleRegion findIrreducibleRegion(ControlFlowGraph cfg, Set<CfgNode> allowed,
            List<CfgNode> order) {
        for (List<CfgNode> scc : stronglyConnectedComponents(cfg, allowed, order)) {
            if (scc.size() < 2) {
                continue;
            }
            Set<CfgNode> region = Collections.newSetFromMap(new IdentityHashMap<>());
            region.addAll(scc);
            List<CfgNode> entries = regionEntries(cfg, region, order);
            if (entries.size() > 1) {
                return new IrreducibleRegion(region, entries);
            }
            // A natural loop with a single entry point. Recurse into the body with the header removed to check for a nested irreducible loop.
            Set<CfgNode> inner = Collections.newSetFromMap(new IdentityHashMap<>());
            inner.addAll(scc);
            inner.remove(entries.get(0));
            IrreducibleRegion nested = findIrreducibleRegion(cfg, inner, order);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /** Returns, in reverse postorder, the nodes that have a predecessor outside the region (i.e. serve as entry points into the region). */
    private static List<CfgNode> regionEntries(ControlFlowGraph cfg, Set<CfgNode> region,
            List<CfgNode> reachable) {
        List<CfgNode> entries = new ArrayList<>();
        for (CfgNode node : reachable) {
            if (!region.contains(node)) {
                continue;
            }
            if (node == cfg.entry()) {
                entries.add(node);
                continue;
            }
            for (CfgNode pred : cfg.predecessors(node)) {
                if (!region.contains(pred)) {
                    entries.add(node);
                    break;
                }
            }
        }
        return entries;
    }

    /**
     * Keeps the first entry point and, for each remaining entry point, duplicates the whole
     * region to make each region single-entry. A duplicated node keeps the original node's
     * Statement and procedureName, and carries the original's id in originalNodeId.
     */
    private static ControlFlowGraph duplicateRegion(ControlFlowGraph cfg, Set<CfgNode> region,
            List<CfgNode> entries) {
        List<CfgNode> nodes = new ArrayList<>(cfg.nodes());
        Map<CfgNode, List<CfgNode>> successors = new LinkedHashMap<>();
        for (CfgNode node : cfg.nodes()) {
            successors.put(node, new ArrayList<>(cfg.successors(node)));
        }
        int nextId = 0;
        for (CfgNode node : cfg.nodes()) {
            nextId = Math.max(nextId, node.id() + 1);
        }
        // Order the region's nodes in reverse postorder to keep duplication deterministic.
        List<CfgNode> orderedRegion = new ArrayList<>();
        for (CfgNode node : cfg.nodes()) {
            if (region.contains(node)) {
                orderedRegion.add(node);
            }
        }

        for (int i = 1; i < entries.size(); i++) {
            CfgNode entryToRedirect = entries.get(i);
            Map<CfgNode, CfgNode> copyOf = new IdentityHashMap<>();
            for (CfgNode original : orderedRegion) {
                int provenance = original.originalNodeId().orElse(original.id());
                CfgNode copy = new CfgNode(nextId++, original.kind(),
                        original.statement().orElse(null), original.procedureName(),
                        Optional.of(provenance));
                copyOf.put(original, copy);
                nodes.add(copy);
                successors.put(copy, new ArrayList<>());
            }
            // Copy the region's internal edges and its edges out of the region to the duplicate.
            for (CfgNode original : orderedRegion) {
                CfgNode copy = copyOf.get(original);
                for (CfgNode target : cfg.successors(original)) {
                    CfgNode mapped = region.contains(target) ? copyOf.get(target) : target;
                    successors.get(copy).add(mapped);
                }
            }
            // Redirect edges from outside the region into this entry point over to the duplicate.
            CfgNode entryCopy = copyOf.get(entryToRedirect);
            for (CfgNode pred : cfg.predecessors(entryToRedirect)) {
                if (region.contains(pred)) {
                    continue;
                }
                List<CfgNode> predEdges = successors.get(pred);
                for (int e = 0; e < predEdges.size(); e++) {
                    if (predEdges.get(e) == entryToRedirect) {
                        predEdges.set(e, entryCopy);
                    }
                }
            }
        }

        return new ControlFlowGraph(cfg.programId(), nodes, cfg.entry(), cfg.exit(), successors);
    }

    // --- Tarjan's strongly connected components (iterative implementation, deterministic) ---

    private static List<List<CfgNode>> stronglyConnectedComponents(ControlFlowGraph cfg,
            Set<CfgNode> allowed, List<CfgNode> order) {
        Map<CfgNode, Integer> index = new IdentityHashMap<>();
        Map<CfgNode, Integer> lowlink = new IdentityHashMap<>();
        Set<CfgNode> onStack = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<CfgNode> componentStack = new ArrayDeque<>();
        List<List<CfgNode>> components = new ArrayList<>();
        int[] counter = {0};
        for (CfgNode start : order) {
            if (!allowed.contains(start) || index.containsKey(start)) {
                continue;
            }
            Deque<CfgNode> callStack = new ArrayDeque<>();
            Deque<Iterator<CfgNode>> iterStack = new ArrayDeque<>();
            open(start, index, lowlink, onStack, componentStack, counter);
            callStack.push(start);
            iterStack.push(allowedSuccessors(cfg, start, allowed).iterator());
            while (!callStack.isEmpty()) {
                CfgNode node = callStack.peek();
                Iterator<CfgNode> it = iterStack.peek();
                boolean descended = false;
                while (it.hasNext()) {
                    CfgNode next = it.next();
                    if (!index.containsKey(next)) {
                        open(next, index, lowlink, onStack, componentStack, counter);
                        callStack.push(next);
                        iterStack.push(allowedSuccessors(cfg, next, allowed).iterator());
                        descended = true;
                        break;
                    } else if (onStack.contains(next)) {
                        lowlink.put(node, Math.min(lowlink.get(node), index.get(next)));
                    }
                }
                if (descended) {
                    continue;
                }
                if (lowlink.get(node).equals(index.get(node))) {
                    List<CfgNode> component = new ArrayList<>();
                    CfgNode member;
                    do {
                        member = componentStack.pop();
                        onStack.remove(member);
                        component.add(member);
                    } while (member != node);
                    components.add(component);
                }
                callStack.pop();
                iterStack.pop();
                if (!callStack.isEmpty()) {
                    CfgNode parent = callStack.peek();
                    lowlink.put(parent, Math.min(lowlink.get(parent), lowlink.get(node)));
                }
            }
        }
        return components;
    }

    private static List<CfgNode> allowedSuccessors(ControlFlowGraph cfg, CfgNode node,
            Set<CfgNode> allowed) {
        List<CfgNode> result = new ArrayList<>();
        for (CfgNode succ : cfg.successors(node)) {
            if (allowed.contains(succ)) {
                result.add(succ);
            }
        }
        return result;
    }

    private static void open(CfgNode node, Map<CfgNode, Integer> index, Map<CfgNode, Integer> lowlink,
            Set<CfgNode> onStack, Deque<CfgNode> componentStack, int[] counter) {
        index.put(node, counter[0]);
        lowlink.put(node, counter[0]);
        counter[0]++;
        onStack.add(node);
        componentStack.push(node);
    }
}
