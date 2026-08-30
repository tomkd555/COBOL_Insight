package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.dataflow.ProgramDataFlow;
import jp.cobolinsight.core.dataflow.TaintKind;
import jp.cobolinsight.core.dataflow.TaintStep;
import jp.cobolinsight.core.dataflow.ValueInterval;

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
 * Implementation of {@link ProgramDataFlow}. Holds each node's fact set as settled by the
 * fixed-point analyses, looked up by CfgNode identity. The interval value range (intervalAt)
 * holds each node's entry variable interval and returns empty when untracked. Taint is held as
 * {@link TaintFact}, which carries its propagation source; taintedAt extracts and returns just
 * the variable names.
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
        // Traces back toward the taint source breadth-first, taking the path up to the first
        // source reached. Because the same fact is never expanded twice, this terminates in
        // finite time even for taint that involves a loop.
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

    /** Lists the path from the taint source to the originating fact. A declaration-derived fact that has no CFG node is not included in the path. */
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
     * An index from (node id, variable) to the facts that carry that pair. Since the incoming
     * fact sets are indexed by node identity, their iteration order is not stable across runs,
     * so this is built deterministically by scanning in node definition order.
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
