package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.dataflow.DataFlowFacts;
import jp.cobolinsight.core.dataflow.ProgramDataFlow;
import jp.cobolinsight.core.dataflow.ValueInterval;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.Statement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs fixed-point analyses over the semantic model and control flow graph to build a
 * {@link ProgramDataFlow}. The three analyses reaching definitions (forward), taint tracking
 * (forward, per kind), and liveness (backward) run on {@link WorklistSolver}; interval value
 * ranges are computed with the dedicated solver in {@link IntervalAnalysis} because that lattice
 * is not finite.
 */
public final class DataFlowEngine {

    /** Suffixes of data names treated as holding sensitive information, matching the naming convention for personal ID numbers, account numbers, and card numbers. */
    private static final List<String> SENSITIVE_SUFFIXES = List.of("-SSN", "-ACCT-NO", "-CARD-NO");

    private DataFlowEngine() {
    }

    /** Analyzes a single program. cfg must be the same instance built from this model. */
    public static ProgramDataFlow analyze(CobolSemanticModel model, ControlFlowGraph cfg) {
        // 1. First settle the def/use for each node.
        Map<CfgNode, DefUse> defUseByNode = new IdentityHashMap<>();
        Map<CfgNode, Set<String>> externalSourceByNode = new IdentityHashMap<>();
        for (CfgNode node : cfg.nodes()) {
            if (node.statement().isPresent()) {
                Statement statement = node.statement().get();
                defUseByNode.put(node, DefUseAnalyzer.extract(statement));
                externalSourceByNode.put(node, DefUseAnalyzer.externalInputTargets(statement));
            } else {
                defUseByNode.put(node, DefUse.EMPTY);
                externalSourceByNode.put(node, Set.of());
            }
        }

        Set<String> uninitVars = uninitializedVariables(model);
        Set<String> sensitiveVars = sensitiveVariables(model);

        Map<CfgNode, Set<Definition>> reachingIn =
                reachingDefinitions(cfg, defUseByNode, uninitVars, subordinatesByGroup(model));
        // Taint runs as an independent analysis per kind. External input treats the receiving
        // statement as the taint source, while sensitive data treats the declaration itself as
        // the source, so the same transfer function is applied twice, varying only how the
        // taint source is supplied.
        Map<CfgNode, Set<TaintFact>> externalTaintIn =
                taint(cfg, defUseByNode, externalSourceByNode, Set.of());
        Map<CfgNode, Set<TaintFact>> sensitiveTaintIn =
                taint(cfg, defUseByNode, emptyExternalSources(cfg), sensitiveVars);
        Map<CfgNode, Set<String>> liveOut = liveness(cfg, defUseByNode);
        Map<CfgNode, Map<String, ValueInterval>> intervalIn = IntervalAnalysis.run(cfg, model);

        return new ProgramDataFlowFacts(model.programId(), cfg.nodes(), defUseByNode, reachingIn,
                externalTaintIn, sensitiveTaintIn, liveOut, intervalIn);
    }

    /** Analyzes multiple programs together and returns a result container indexed by programId. */
    public static DataFlowFacts analyzeAll(Collection<CobolSemanticModel> models,
            ControlFlowGraphs graphs) {
        List<ProgramDataFlow> flows = new ArrayList<>();
        for (CobolSemanticModel model : models) {
            graphs.of(model).ifPresent(cfg -> flows.add(analyze(model, cfg)));
        }
        return new DataFlowFacts(flows);
    }

    // ---- Reaching definitions (forward, may) ----

    private static Map<CfgNode, Set<Definition>> reachingDefinitions(ControlFlowGraph cfg,
            Map<CfgNode, DefUse> defUseByNode, Set<String> uninitVars,
            Map<String, Set<String>> subordinatesByGroup) {
        Set<Definition> boundary = new LinkedHashSet<>();
        for (String var : uninitVars) {
            boundary.add(new Definition(var, cfg.entry().id(), true));
        }
        return WorklistSolver.solve(cfg, WorklistSolver.Direction.FORWARD, boundary, (node, in) -> {
            Set<String> defVars =
                    withSubordinates(defUseByNode.get(node).defs(), subordinatesByGroup);
            Set<Definition> out = new LinkedHashSet<>();
            for (Definition d : in) {
                if (!defVars.contains(d.variable())) {
                    out.add(d);
                }
            }
            for (String var : defVars) {
                out.add(new Definition(var, node.id(), false));
            }
            return out;
        });
    }

    /**
     * Group item name -> the names of all its subordinate items. An assignment to a group item
     * rewrites the whole storage area, so it also replaces the definitions of the subordinate
     * items. Without this correspondence, a subordinate item initialized through its group item
     * would keep receiving the entry's uninitialized definition.
     */
    private static Map<String, Set<String>> subordinatesByGroup(CobolSemanticModel model) {
        Map<String, Set<String>> result = new java.util.LinkedHashMap<>();
        for (DataItem item : model.dataItems()) {
            collectSubordinates(item, result);
        }
        return result;
    }

    private static Set<String> collectSubordinates(DataItem item, Map<String, Set<String>> result) {
        Set<String> all = new LinkedHashSet<>();
        for (DataItem child : item.children()) {
            all.add(child.name().toUpperCase(Locale.ROOT));
            all.addAll(collectSubordinates(child, result));
        }
        if (!all.isEmpty()) {
            result.computeIfAbsent(item.name().toUpperCase(Locale.ROOT), k -> new LinkedHashSet<>())
                    .addAll(all);
        }
        return all;
    }

    private static Set<String> withSubordinates(Set<String> defs,
            Map<String, Set<String>> subordinatesByGroup) {
        Set<String> expanded = new LinkedHashSet<>(defs);
        for (String def : defs) {
            expanded.addAll(subordinatesByGroup.getOrDefault(def, Set.of()));
        }
        return expanded;
    }

    // ---- Taint tracking (forward, may) ----

    private static Map<CfgNode, Set<TaintFact>> taint(ControlFlowGraph cfg,
            Map<CfgNode, DefUse> defUseByNode, Map<CfgNode, Set<String>> externalSourceByNode,
            Set<String> alwaysTainted) {
        Set<TaintFact> boundary = new LinkedHashSet<>();
        for (String var : alwaysTainted) {
            boundary.add(TaintFact.declared(var));
        }
        return WorklistSolver.solve(cfg, WorklistSolver.Direction.FORWARD, boundary, (node, in) -> {
            DefUse defUse = defUseByNode.get(node);
            Set<String> defVars = defUse.defs();
            Set<TaintFact> out = new LinkedHashSet<>();
            // kill: drop the prior taint of a variable overwritten by this assignment (a
            // constant taint that comes from the identifier itself is kept).
            for (TaintFact fact : in) {
                if (!defVars.contains(fact.variable())
                        || alwaysTainted.contains(fact.variable())) {
                    out.add(fact);
                }
            }
            // propagate: if any referenced variable is tainted, taint this statement's
            // definition targets too. Creating a fact per propagation source keeps fact
            // generation monotonic over the incoming set, so it converges to a fixed point.
            for (TaintFact fact : in) {
                if (defUse.uses().contains(fact.variable())) {
                    for (String def : defVars) {
                        out.add(TaintFact.propagated(def, node.id(), fact));
                    }
                }
            }
            // source: generate the receiving target of an external input as a taint source.
            for (String var : externalSourceByNode.get(node)) {
                out.add(TaintFact.source(var, node.id()));
            }
            // Constant taint (sensitive names) is kept alive at every node.
            for (String var : alwaysTainted) {
                out.add(TaintFact.declared(var));
            }
            return out;
        });
    }

    // ---- Liveness (backward, may) ----

    private static Map<CfgNode, Set<String>> liveness(ControlFlowGraph cfg,
            Map<CfgNode, DefUse> defUseByNode) {
        return WorklistSolver.solve(cfg, WorklistSolver.Direction.BACKWARD, Set.of(), (node, out) -> {
            DefUse defUse = defUseByNode.get(node);
            Set<String> in = new LinkedHashSet<>(out);
            in.removeAll(defUse.defs());
            in.addAll(defUse.uses());
            return in;
        });
    }

    // ---- Extracting the variable set ----

    /** Items without a VALUE clause are candidates for uninitialized status. They become the targets for which a synthetic definition is created at entry. */
    private static Set<String> uninitializedVariables(CobolSemanticModel model) {
        Set<String> result = new LinkedHashSet<>();
        for (DataItem item : model.dataItems()) {
            collectUninitialized(item, result);
        }
        return result;
    }

    private static void collectUninitialized(DataItem item, Set<String> result) {
        if (item.value().isEmpty()) {
            result.add(item.name().toUpperCase(Locale.ROOT));
        }
        for (DataItem child : item.children()) {
            collectUninitialized(child, result);
        }
    }

    private static Set<String> sensitiveVariables(CobolSemanticModel model) {
        Set<String> result = new LinkedHashSet<>();
        for (DataItem item : model.dataItems()) {
            collectSensitive(item, result);
        }
        return result;
    }

    private static void collectSensitive(DataItem item, Set<String> result) {
        String name = item.name().toUpperCase(Locale.ROOT);
        for (String suffix : SENSITIVE_SUFFIXES) {
            if (name.endsWith(suffix)) {
                result.add(name);
                break;
            }
        }
        for (DataItem child : item.children()) {
            collectSensitive(child, result);
        }
    }

    private static Map<CfgNode, Set<String>> emptyExternalSources(ControlFlowGraph cfg) {
        Map<CfgNode, Set<String>> map = new IdentityHashMap<>();
        for (CfgNode node : cfg.nodes()) {
            map.put(node, Set.of());
        }
        return map;
    }
}
