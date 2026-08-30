package jp.cobolinsight.core.cfg;

import jp.cobolinsight.core.semantic.CobolSemanticModel;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A cross-program CFG container. Indexed by programId, and used as the key type that rules/cli
 * receive via {@code AnalysisContext.artifact()}.
 */
public final class ControlFlowGraphs {

    private final Map<String, ControlFlowGraph> byProgramId;

    /** When multiple graphs share the same programId, only the first one in input order is kept. */
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

    /** All CFGs (input order). */
    public Collection<ControlFlowGraph> all() {
        return byProgramId.values();
    }
}
