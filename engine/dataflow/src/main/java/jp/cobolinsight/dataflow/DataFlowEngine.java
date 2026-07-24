package jp.cobolinsight.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraphs;
import jp.cobolinsight.engineapi.dataflow.DataFlowFacts;
import jp.cobolinsight.engineapi.dataflow.ProgramDataFlow;
import jp.cobolinsight.engineapi.dataflow.ValueInterval;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.DataItem;
import jp.cobolinsight.engineapi.semantic.Statement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 意味モデルと制御フローグラフから不動点解析を実行し、{@link ProgramDataFlow} を構築する。
 * 到達定義(前進)・汚染追跡(前進・種別別)・生存(後進)の3解析を {@link WorklistSolver} 上で
 * 回す。区間値域(intervalAt)はこの段では未追跡で常に empty を返し、後半で差し込む。
 */
public final class DataFlowEngine {

    private static final List<String> SENSITIVE_SUFFIXES = List.of("-SSN", "-ACCT-NO", "-CARD-NO");

    private DataFlowEngine() {
    }

    /** 1プログラムを解析する。cfg は当該 model から構築した同一インスタンスであること。 */
    public static ProgramDataFlow analyze(CobolSemanticModel model, ControlFlowGraph cfg) {
        // 1. ノード別の def/use を先に確定する。
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
                reachingDefinitions(cfg, defUseByNode, uninitVars);
        Map<CfgNode, Set<String>> externalTaintIn =
                taint(cfg, defUseByNode, externalSourceByNode, Set.of());
        Map<CfgNode, Set<String>> sensitiveTaintIn =
                taint(cfg, defUseByNode, emptyExternalSources(cfg), sensitiveVars);
        Map<CfgNode, Set<String>> liveOut = liveness(cfg, defUseByNode);
        Map<CfgNode, Map<String, ValueInterval>> intervalIn = IntervalAnalysis.run(cfg, model);

        return new ProgramDataFlowFacts(model.programId(), defUseByNode, reachingIn,
                externalTaintIn, sensitiveTaintIn, liveOut, intervalIn);
    }

    /** 複数プログラムをまとめて解析し、programId で索引化した結果コンテナを返す。 */
    public static DataFlowFacts analyzeAll(Collection<CobolSemanticModel> models,
            ControlFlowGraphs graphs) {
        List<ProgramDataFlow> flows = new ArrayList<>();
        for (CobolSemanticModel model : models) {
            graphs.of(model).ifPresent(cfg -> flows.add(analyze(model, cfg)));
        }
        return new DataFlowFacts(flows);
    }

    // ---- 到達定義(前進・may) ----

    private static Map<CfgNode, Set<Definition>> reachingDefinitions(ControlFlowGraph cfg,
            Map<CfgNode, DefUse> defUseByNode, Set<String> uninitVars) {
        Set<Definition> boundary = new LinkedHashSet<>();
        for (String var : uninitVars) {
            boundary.add(new Definition(var, cfg.entry().id(), true));
        }
        return WorklistSolver.solve(cfg, WorklistSolver.Direction.FORWARD, boundary, (node, in) -> {
            Set<String> defVars = defUseByNode.get(node).defs();
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

    // ---- 汚染追跡(前進・may) ----

    private static Map<CfgNode, Set<String>> taint(ControlFlowGraph cfg,
            Map<CfgNode, DefUse> defUseByNode, Map<CfgNode, Set<String>> externalSourceByNode,
            Set<String> alwaysTainted) {
        Set<String> boundary = new LinkedHashSet<>(alwaysTainted);
        return WorklistSolver.solve(cfg, WorklistSolver.Direction.FORWARD, boundary, (node, in) -> {
            DefUse defUse = defUseByNode.get(node);
            Set<String> defVars = defUse.defs();
            Set<String> out = new LinkedHashSet<>(in);
            // kill: 代入で上書きされる変数の旧汚染を落とす(識別子由来の恒常汚染は残す)。
            for (String def : defVars) {
                if (!alwaysTainted.contains(def)) {
                    out.remove(def);
                }
            }
            // 伝播: 参照変数のいずれかが汚染なら、この文の定義先も汚染する。
            if (!Collections.disjoint(defUse.uses(), in)) {
                out.addAll(defVars);
            }
            // source: 外部入力の受信先を汚染源として生成する。
            out.addAll(externalSourceByNode.get(node));
            // 恒常汚染(機密名義)は全ノードで維持する。
            out.addAll(alwaysTainted);
            return out;
        });
    }

    // ---- 生存(後進・may) ----

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

    // ---- 変数集合の抽出 ----

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
