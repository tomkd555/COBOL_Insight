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
 * 意味モデルと制御フローグラフから不動点解析を実行し、{@link ProgramDataFlow} を構築する。
 * 到達定義(前進)・汚染追跡(前進・種別別)・生存(後進)の3解析を {@link WorklistSolver} 上で
 * 回し、区間値域は束が有限でないため {@link IntervalAnalysis} の専用ソルバで求める。
 */
public final class DataFlowEngine {

    /** 機密情報を持つと見なすデータ名の末尾。個人番号・口座番号・カード番号の命名慣行に合わせる。 */
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
                reachingDefinitions(cfg, defUseByNode, uninitVars, subordinatesByGroup(model));
        // 汚染は種別ごとに独立した解析として回す。外部入力は受信文を汚染源とし、機密は宣言そのものを
        // 汚染源とするため、同じ転送関数を汚染源の与え方だけ変えて2度適用する。
        Map<CfgNode, Set<TaintFact>> externalTaintIn =
                taint(cfg, defUseByNode, externalSourceByNode, Set.of());
        Map<CfgNode, Set<TaintFact>> sensitiveTaintIn =
                taint(cfg, defUseByNode, emptyExternalSources(cfg), sensitiveVars);
        Map<CfgNode, Set<String>> liveOut = liveness(cfg, defUseByNode);
        Map<CfgNode, Map<String, ValueInterval>> intervalIn = IntervalAnalysis.run(cfg, model);

        return new ProgramDataFlowFacts(model.programId(), cfg.nodes(), defUseByNode, reachingIn,
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
     * 集団項目名 → 従属する全項目名。集団項目への代入は記憶領域全体を書き換えるため、従属項目の
     * 定義も同時に置き換える。この対応を持たないと、集団項目で初期化した従属項目へ入口の
     * 未初期化定義が届き続ける。
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

    // ---- 汚染追跡(前進・may) ----

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
            // kill: 代入で上書きされる変数の旧汚染を落とす(識別子由来の恒常汚染は残す)。
            for (TaintFact fact : in) {
                if (!defVars.contains(fact.variable())
                        || alwaysTainted.contains(fact.variable())) {
                    out.add(fact);
                }
            }
            // 伝播: 参照変数のいずれかが汚染なら、この文の定義先も汚染する。伝播元ごとに事実を
            // 立てることで、事実の生成が流入集合に対して単調になり不動点へ収束する。
            for (TaintFact fact : in) {
                if (defUse.uses().contains(fact.variable())) {
                    for (String def : defVars) {
                        out.add(TaintFact.propagated(def, node.id(), fact));
                    }
                }
            }
            // source: 外部入力の受信先を汚染源として生成する。
            for (String var : externalSourceByNode.get(node)) {
                out.add(TaintFact.source(var, node.id()));
            }
            // 恒常汚染(機密名義)は全ノードで維持する。
            for (String var : alwaysTainted) {
                out.add(TaintFact.declared(var));
            }
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

    /** VALUE 句を持たない項目を未初期化の候補とする。入口で合成定義を立てる対象になる。 */
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
