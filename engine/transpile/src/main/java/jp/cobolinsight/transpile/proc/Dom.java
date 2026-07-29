package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.engineapi.cfg.CfgNode;

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
 * 単一根の支配木を Cooper-Harvey-Kennedy の反復法で構築する補助。前方の支配木(根=入口、後続=succ)と
 * 後方の支配木(=後支配木。根=出口、後続=pred)の双方を、succ/pred を入れ替えて同じ手続きで求める。
 * GO TO 構造化の還元で、後退辺の同定(支配)と分岐の合流点(後支配)に用いる。
 */
final class Dom {

    private final Map<CfgNode, CfgNode> idom;

    private Dom(Map<CfgNode, CfgNode> idom) {
        this.idom = idom;
    }

    /** 根から succ でたどれる範囲の即時支配節点。根は自身を指す。 */
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

    /** node の即時支配節点。到達不能なら null。 */
    CfgNode immediate(CfgNode node) {
        return idom.get(node);
    }

    /** dominator が node を支配するか(自身も支配とみなす)。 */
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

    /** 2節点の直近の共通支配節点。postorder 番号の小さい側を即時支配節点へ上げ、番号が一致するまで詰める。 */
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

    /** 根から succ でたどるノードを reverse postorder(先頭が根)で返す。 */
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
