package jp.cobolinsight.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 有限束上の may 解析(合流=∪)を、方向と転送関数を差し替えて回す汎用 worklist ソルバ。
 * 前進(合流=先行ノードの流出)と後進(合流=後続ノードの流出)を切り替えられる。合流が単調な
 * 転送関数に対し、流出集合は反復ごとに非減少で有限束のため必ず不動点へ収束する。
 *
 * <p>結果は各ノードの「流入」集合(流れの向きで転送関数に入る値)を返す。前進解析ではノード
 * 入口の値、後進解析ではノード出口の値に相当する。ノードは同一性({@code IdentityHashMap})で
 * 索引化し、{@link ControlFlowGraph#nodes()} 順に初期投入して決定論的に収束する。
 */
final class WorklistSolver {

    enum Direction {
        FORWARD, BACKWARD
    }

    /** ノードの流入集合から流出集合を計算する転送関数。合流に対して単調であること。 */
    interface Transfer<T> {
        Set<T> apply(CfgNode node, Set<T> input);
    }

    private WorklistSolver() {
    }

    /**
     * 不動点を計算し、各ノードの流入集合を返す。
     *
     * @param boundary 境界ノード(前進=entry、後進=exit)の流入に注入する初期集合
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
