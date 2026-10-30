package jp.cobolinsight.core.dataflow;

import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.semantic.CobolSemanticModel;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Container for cross-program fixed-point analysis results, found by the program as written (the
 * PROGRAM-ID together with the source file); used as the key type that rules/cli receive via
 * {@code AnalysisContext.artifact()}.
 * Follows the same placement and usage pattern as {@link jp.cobolinsight.core.cfg.ControlFlowGraphs}.
 */
public final class DataFlowFacts {

    private final Map<String, ProgramDataFlow> byProgram;

    /** When two results share program and source file, the first in insertion order is kept. */
    public DataFlowFacts(Collection<ProgramDataFlow> flows) {
        Map<String, ProgramDataFlow> map = new LinkedHashMap<>();
        for (ProgramDataFlow flow : flows) {
            map.putIfAbsent(ControlFlowGraphs.key(flow.programId(), flow.sourceFile()), flow);
        }
        this.byProgram = Collections.unmodifiableMap(map);
    }

    /** The result of the program the model was read from. */
    public Optional<ProgramDataFlow> of(CobolSemanticModel model) {
        return Optional.ofNullable(
                byProgram.get(ControlFlowGraphs.key(model.programId(), model.sourceFile())));
    }

    /** Analysis results for all programs (in insertion order). */
    public Collection<ProgramDataFlow> all() {
        return byProgram.values();
    }
}
