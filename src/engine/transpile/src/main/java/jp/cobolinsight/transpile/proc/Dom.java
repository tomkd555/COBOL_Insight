package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.core.cfg.CfgNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Helper that builds a single-root dominator tree using the Cooper-Harvey-Kennedy iterative
 * algorithm. Both the forward dominator tree (root = entry, successors = succ) and the backward
 * dominator tree (i.e. post-dominator tree; root = exit, successors = pred) are computed with the
 * same procedure by swapping succ/pred. Used in GOTO-structuring reduction: identifying back
 * edges (dominance) and branch merge points (post-dominance).
 */
final class Dom {

    private final Map<CfgNode, CfgNode> idom;

    private Dom(Map<CfgNode, CfgNode> idom) {
        this.idom = idom;
    }

    /** Immediate dominator of each node reachable from root via succ. The root dominates itself. */
    static Dom build(CfgNode root, Function<CfgNode, List<CfgNode>> succ,
            Function<CfgNode, List<CfgNode>> pred) {
        List<CfgNode> rpo = reversePostorder(root, succ);
        Map<CfgNode, Integer> postNumber = new IdentityHashMap<>();
        int n = rpo.size();
        for (int i = 0; i < n; i++) {
            postNumber.put(rpo.get(i), n - 1 - i);
        }
        Set<CfgNode> reachable = Collections.newSetFromMap(new IdentityHashMap<>());
        reachable.addAll(rpo);
        Map<CfgNode, CfgNode> idom = new IdentityHashMap<>();
        idom.put(root, root);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (CfgNode b : rpo) {
                if (b == root) {
                    continue;
                }
                CfgNode candidate = null;
                for (CfgNode p : pred.apply(b)) {
                    if (!reachable.contains(p) || !idom.containsKey(p)) {
                        continue;
                    }
                    candidate = (candidate == null) ? p : intersect(p, candidate, idom, postNumber);
                }
                if (candidate != null && idom.get(b) != candidate) {
                    idom.put(b, candidate);
                    changed = true;
                }
            }
        }
        return new Dom(idom);
    }

    /** The immediate dominator of node. Returns null if unreachable. */
    CfgNode immediate(CfgNode node) {
        return idom.get(node);
    }

    /** Whether dominator dominates node (a node is considered to dominate itself). */
    boolean dominates(CfgNode dominator, CfgNode node) {
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

    /**
     * Nearest common dominator of two nodes. Walks the side with the smaller postorder number up
     * to its immediate dominator, repeating until the numbers match.
     */
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

    /** Returns the nodes reachable from root via succ in reverse postorder (root first). */
    static List<CfgNode> reversePostorder(CfgNode root, Function<CfgNode, List<CfgNode>> succ) {
        List<CfgNode> postorder = new ArrayList<>();
        Set<CfgNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<CfgNode> stack = new ArrayDeque<>();
        Deque<Iterator<CfgNode>> iterators = new ArrayDeque<>();
        visited.add(root);
        stack.push(root);
        iterators.push(succ.apply(root).iterator());
        while (!stack.isEmpty()) {
            Iterator<CfgNode> it = iterators.peek();
            if (it.hasNext()) {
                CfgNode next = it.next();
                if (visited.add(next)) {
                    stack.push(next);
                    iterators.push(succ.apply(next).iterator());
                }
            } else {
                postorder.add(stack.pop());
                iterators.pop();
            }
        }
        Collections.reverse(postorder);
        return postorder;
    }
}
