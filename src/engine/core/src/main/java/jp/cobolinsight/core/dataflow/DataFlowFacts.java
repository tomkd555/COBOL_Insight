package jp.cobolinsight.core.dataflow;

import jp.cobolinsight.core.semantic.CobolSemanticModel;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Container for cross-program fixed-point analysis results. Indexed by programId; used as the
 * key type that rules/cli receive via {@code AnalysisContext.artifact()}.
 * Follows the same placement and usage pattern as {@link jp.cobolinsight.core.cfg.ControlFlowGraphs}.
 */
public final class DataFlowFacts {

    private final Map<String, ProgramDataFlow> byProgramId;

    /** When multiple analysis results share the same programId, only the first in insertion order is kept. */
    public DataFlowFacts(Collection<ProgramDataFlow> flows) {
        Map<String, ProgramDataFlow> map = new LinkedHashMap<>();
        for (ProgramDataFlow flow : flows) {
            map.putIfAbsent(flow.programId(), flow);
        }
        this.byProgramId = Collections.unmodifiableMap(map);
    }

    public Optional<ProgramDataFlow> of(String programId) {
        return Optional.ofNullable(byProgramId.get(programId));
    }

    public Optional<ProgramDataFlow> of(CobolSemanticModel model) {
        return of(model.programId());
    }

    /** Analysis results for all programs (in insertion order). */
    public Collection<ProgramDataFlow> all() {
        return byProgramId.values();
    }
}
