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
 * 1プログラムの制御フローグラフ。ノードは生成順(意味モデルの定義順)、後続リストは辺の
 * 追加順を保ち、同一入力から常に同一のグラフを生成する(決定論)。文からノードへの索引は
 * 意味モデルの文インスタンスの同一性で引く。
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

    /** 全ノード(生成順)。 */
    public List<CfgNode> nodes() {
        return nodes;
    }

    public CfgNode entry() {
        return entry;
    }

    public CfgNode exit() {
        return exit;
    }

    /** 直接後続(辺の追加順)。 */
    public List<CfgNode> successors(CfgNode node) {
        return successors.getOrDefault(node, List.of());
    }

    /** 直接先行(ノード生成順)。 */
    public List<CfgNode> predecessors(CfgNode node) {
        return predecessors.getOrDefault(node, List.of());
    }

    /** 意味モデルの文(同一インスタンス)に対応するノード。 */
    public Optional<CfgNode> nodeOf(Statement statement) {
        return Optional.ofNullable(nodeByStatement.get(statement));
    }

    /** 辺の総数。 */
    public int edgeCount() {
        return successors.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 入口から後続辺を単純にたどって到達できるノード集合。全ての辺種(PERFORM復帰辺・段落流下辺を
     * 含む)をたどる。到達順を保つため反復順序は決定的。到達不能コード検出(R011)の基盤に使う。
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
