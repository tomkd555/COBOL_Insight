package jp.cobolinsight.engineapi.cfg;

import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * プログラム横断のCFGコンテナ。programId で索引化し、rules・cli が
 * {@code AnalysisContext.artifact()} 経由で受け取るキー型として使う。
 */
public final class ControlFlowGraphs {

    private final Map<String, ControlFlowGraph> byProgramId;

    /** 同じ programId のグラフが複数含まれる場合は、投入順で最初のものだけを採る。 */
    public ControlFlowGraphs(Collection<ControlFlowGraph> graphs) {
        Map<String, ControlFlowGraph> map = new LinkedHashMap<>();
        for (ControlFlowGraph graph : graphs) {
            map.putIfAbsent(graph.programId(), graph);
        }
        this.byProgramId = Collections.unmodifiableMap(map);
    }

    public Optional<ControlFlowGraph> of(String programId) {
        return Optional.ofNullable(byProgramId.get(programId));
    }

    public Optional<ControlFlowGraph> of(CobolSemanticModel model) {
        return of(model.programId());
    }

    /** 全CFG(投入順)。 */
    public Collection<ControlFlowGraph> all() {
        return byProgramId.values();
    }
}
