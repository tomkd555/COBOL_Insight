package jp.cobolinsight.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.semantic.Statement;

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
 * GO TO を含む制御フローグラフを構造化可能(可約)な形へ正規化する。支配木(Cooper-Harvey-Kennedy
 * の反復法)から後退辺を同定して可約性を判定し、可約なら同値グラフをそのまま返す。不可約な強連結
 * 領域は controlled node duplication(Hendren, DOI:10.1109/ICCL.1994.288377)で各流入経路ごとに
 * 領域を複製して単一入口化する。複製ノードは元ノードの Statement・procedureName・原座標を保ち、
 * originalNodeId で複製元へ写像できる。
 */
public final class GotoNormalizer {

    private GotoNormalizer() {
    }

    /** 正規化した制御フローグラフ。可約なら入力をそのまま返す。 */
    public static ControlFlowGraph normalize(ControlFlowGraph cfg) {
        ControlFlowGraph current = cfg;
        int guard = 0;
        int nodeCount = current.nodes().size();
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

    /** 入口から到達する部分グラフが可約(構造化可能)なら true。 */
    public static boolean isReducible(ControlFlowGraph cfg) {
        List<CfgNode> reachable = reversePostorder(cfg);
        Map<CfgNode, CfgNode> idom = dominators(cfg, reachable);
        Set<CfgNode> reachableSet = Collections.newSetFromMap(new IdentityHashMap<>());
        reachableSet.addAll(reachable);
        // 後退辺(head が tail を支配する辺)を除いた前方辺の集合が非循環なら可約。
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

    // --- 支配木 ---

    private static Map<CfgNode, CfgNode> dominators(ControlFlowGraph cfg, List<CfgNode> reversePostorder) {
        Map<CfgNode, Integer> postNumber = new IdentityHashMap<>();
        int n = reversePostorder.size();
        for (int i = 0; i < n; i++) {
            // reversePostorder.get(0) は入口。postorder 番号は入口が最大になるよう割り当てる。
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

    /** 入口から到達するノードを reverse postorder(先頭が入口)で返す。 */
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

    // --- 不可約領域の複製による単一入口化 ---

    /** 領域と、その流入口(複数なら不可約)。 */
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
     * allowed 部分グラフの強連結成分を調べ、複数の流入口を持つ(不可約な)領域を返す。単一流入口の
     * 自然ループはヘッダを除いた本体へ再帰し、入れ子の不可約ループも検出する。決定的な探索順を保つ。
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
            // 単一流入口の自然ループ。ヘッダを除いた本体に入れ子の不可約ループが無いか再帰。
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

    /** 領域外に先行を持つ(=領域への流入口となる)ノードを reverse postorder 順で返す。 */
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
     * 先頭の流入口を残し、残る各流入口ごとに領域全体を複製して、それぞれの領域を単一入口化する。
     * 複製ノードは元ノードの Statement・procedureName を保ち、originalNodeId に複製元idを持つ。
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
        // 領域ノードを reverse postorder 順に並べて複製の決定性を保つ。
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
            // 領域内部辺・領域外への脱出辺を複製へ写す。
            for (CfgNode original : orderedRegion) {
                CfgNode copy = copyOf.get(original);
                for (CfgNode target : cfg.successors(original)) {
                    CfgNode mapped = region.contains(target) ? copyOf.get(target) : target;
                    successors.get(copy).add(mapped);
                }
            }
            // この流入口への領域外からの辺を複製側へ付け替える。
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

        Map<Statement, CfgNode> byStatement = new IdentityHashMap<>();
        for (CfgNode node : nodes) {
            if (node.originalNodeId().isEmpty()) {
                node.statement().ifPresent(statement -> byStatement.put(statement, node));
            }
        }
        return new ControlFlowGraph(cfg.programId(), nodes, cfg.entry(), cfg.exit(),
                successors, byStatement);
    }

    // --- Tarjan の強連結成分(反復実装、決定的) ---

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
