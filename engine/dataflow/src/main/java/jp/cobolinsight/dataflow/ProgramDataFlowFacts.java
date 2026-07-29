package jp.cobolinsight.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.dataflow.ProgramDataFlow;
import jp.cobolinsight.engineapi.dataflow.TaintKind;
import jp.cobolinsight.engineapi.dataflow.TaintStep;
import jp.cobolinsight.engineapi.dataflow.ValueInterval;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ProgramDataFlow} の実装。不動点解析で確定した各ノードの事実集合を保持し、CfgNode の
 * 同一性で引く。区間値域(intervalAt)はノード入口の変数区間を保持し、追跡外は empty を返す。
 * 汚染は伝播元を持つ {@link TaintFact} で保持し、taintedAt は変数名だけを取り出して返す。
 */
final class ProgramDataFlowFacts implements ProgramDataFlow {

    private final String programId;
    private final Map<CfgNode, DefUse> defUseByNode;
    private final Map<CfgNode, Set<Definition>> reachingIn;
    private final Map<CfgNode, Set<TaintFact>> externalTaintIn;
    private final Map<CfgNode, Set<TaintFact>> sensitiveTaintIn;
    private final Map<CfgNode, Set<String>> liveOut;
    private final Map<CfgNode, Map<String, ValueInterval>> intervalIn;
    private final Map<Integer, CfgNode> nodeById;
    private final Map<Integer, Map<String, List<TaintFact>>> externalFactsByKey;
    private final Map<Integer, Map<String, List<TaintFact>>> sensitiveFactsByKey;

    ProgramDataFlowFacts(String programId, List<CfgNode> nodes, Map<CfgNode, DefUse> defUseByNode,
            Map<CfgNode, Set<Definition>> reachingIn, Map<CfgNode, Set<TaintFact>> externalTaintIn,
            Map<CfgNode, Set<TaintFact>> sensitiveTaintIn, Map<CfgNode, Set<String>> liveOut,
            Map<CfgNode, Map<String, ValueInterval>> intervalIn) {
        this.programId = programId;
        this.defUseByNode = defUseByNode;
        this.reachingIn = reachingIn;
        this.externalTaintIn = externalTaintIn;
        this.sensitiveTaintIn = sensitiveTaintIn;
        this.liveOut = liveOut;
        this.intervalIn = intervalIn;
        Map<Integer, CfgNode> byId = new LinkedHashMap<>();
        for (CfgNode node : nodes) {
            byId.putIfAbsent(node.id(), node);
        }
        this.nodeById = byId;
        this.externalFactsByKey = indexFacts(nodes, externalTaintIn);
        this.sensitiveFactsByKey = indexFacts(nodes, sensitiveTaintIn);
    }

    @Override
    public String programId() {
        return programId;
    }

    @Override
    public boolean mayReachUninitialized(CfgNode useNode, String varName) {
        String var = normalize(varName);
        Set<Definition> reaching = reachingIn.get(useNode);
        if (reaching == null) {
            return false;
        }
        for (Definition d : reaching) {
            if (d.synthetic() && d.variable().equals(var)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<ValueInterval> intervalAt(CfgNode node, String varName) {
        Map<String, ValueInterval> state = intervalIn.get(node);
        if (state == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(state.get(normalize(varName)));
    }

    @Override
    public Set<String> taintedAt(CfgNode node, TaintKind kind) {
        Set<TaintFact> facts = factsIn(kind).get(node);
        if (facts == null) {
            return Set.of();
        }
        Set<String> variables = new LinkedHashSet<>();
        for (TaintFact fact : facts) {
            variables.add(fact.variable());
        }
        return Set.copyOf(variables);
    }

    @Override
    public List<TaintStep> taintPathTo(CfgNode node, String varName, TaintKind kind) {
        Set<TaintFact> facts = factsIn(kind).get(node);
        if (facts == null) {
            return List.of();
        }
        String var = normalize(varName);
        // 汚染源へ向かって幅優先で遡り、最初に到達した汚染源までを経路とする。同一の事実を二度
        // 展開しないため、ループを含む汚染でも有限で終わる。
        Deque<TaintFact> queue = new ArrayDeque<>();
        Map<TaintFact, TaintFact> childOf = new LinkedHashMap<>();
        for (TaintFact fact : facts) {
            if (fact.variable().equals(var) && fact.nodeId() != TaintFact.DECLARATION) {
                childOf.put(fact, null);
                queue.addLast(fact);
            }
        }
        while (!queue.isEmpty()) {
            TaintFact fact = queue.removeFirst();
            if (fact.fromNodeId() == TaintFact.NO_SOURCE) {
                return pathFrom(fact, childOf);
            }
            for (TaintFact parent : parentsOf(kind, fact)) {
                if (!childOf.containsKey(parent)) {
                    childOf.put(parent, fact);
                    queue.addLast(parent);
                }
            }
        }
        return List.of();
    }

    @Override
    public Set<String> defsAt(CfgNode node) {
        DefUse defUse = defUseByNode.get(node);
        return defUse == null ? Set.of() : defUse.defs();
    }

    @Override
    public Set<String> usesAt(CfgNode node) {
        DefUse defUse = defUseByNode.get(node);
        return defUse == null ? Set.of() : defUse.uses();
    }

    @Override
    public Set<String> liveOut(CfgNode node) {
        Set<String> set = liveOut.get(node);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    private Map<CfgNode, Set<TaintFact>> factsIn(TaintKind kind) {
        return kind == TaintKind.SENSITIVE ? sensitiveTaintIn : externalTaintIn;
    }

    private List<TaintFact> parentsOf(TaintKind kind, TaintFact fact) {
        Map<Integer, Map<String, List<TaintFact>>> index =
                kind == TaintKind.SENSITIVE ? sensitiveFactsByKey : externalFactsByKey;
        Map<String, List<TaintFact>> byVariable = index.get(fact.fromNodeId());
        if (byVariable == null) {
            return List.of();
        }
        List<TaintFact> parents = byVariable.get(fact.fromVariable());
        return parents == null ? List.of() : parents;
    }

    /** 汚染源から起点の事実までを並べる。CFG ノードを持たない宣言由来の事実は経路に含めない。 */
    private List<TaintStep> pathFrom(TaintFact source, Map<TaintFact, TaintFact> childOf) {
        List<TaintStep> steps = new ArrayList<>();
        for (TaintFact fact = source; fact != null; fact = childOf.get(fact)) {
            CfgNode owner = nodeById.get(fact.nodeId());
            if (owner != null) {
                steps.add(new TaintStep(owner, fact.variable(),
                        Optional.ofNullable(fact.fromVariable())));
            }
        }
        return List.copyOf(steps);
    }

    /**
     * (ノードid, 変数)から、その組を持つ事実へ引く索引。流入集合はノードの同一性で索引化されて
     * おり反復順が実行間で一定でないため、ノード定義順に走査して決定論的に組み立てる。
     */
    private static Map<Integer, Map<String, List<TaintFact>>> indexFacts(List<CfgNode> nodes,
            Map<CfgNode, Set<TaintFact>> factsIn) {
        Map<Integer, Map<String, List<TaintFact>>> index = new LinkedHashMap<>();
        for (CfgNode node : nodes) {
            Set<TaintFact> facts = factsIn.get(node);
            if (facts == null) {
                continue;
            }
            for (TaintFact fact : facts) {
                List<TaintFact> bucket = index
                        .computeIfAbsent(fact.nodeId(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(fact.variable(), k -> new ArrayList<>());
                if (!bucket.contains(fact)) {
                    bucket.add(fact);
                }
            }
        }
        return index;
    }

    private static String normalize(String varName) {
        return varName.toUpperCase(Locale.ROOT);
    }
}
