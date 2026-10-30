package jp.cobolinsight.core.cfg;

import jp.cobolinsight.core.semantic.CobolSemanticModel;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A cross-program CFG container, used as the key type that rules/cli receive via
 * {@code AnalysisContext.artifact()}. A graph is found by its program as written: the PROGRAM-ID
 * together with the source file, so two files that declare one PROGRAM-ID each have their own.
 */
public final class ControlFlowGraphs {

    private final Map<String, ControlFlowGraph> byProgram;

    /** When two graphs share program and source file, the first one in input order is kept. */
    public ControlFlowGraphs(Collection<ControlFlowGraph> graphs) {
        Map<String, ControlFlowGraph> map = new LinkedHashMap<>();
        for (ControlFlowGraph graph : graphs) {
            map.putIfAbsent(key(graph.programId(), graph.sourceFile()), graph);
        }
        this.byProgram = Collections.unmodifiableMap(map);
    }

    /** The graph of the program the model was read from. */
    public Optional<ControlFlowGraph> of(CobolSemanticModel model) {
        return Optional.ofNullable(byProgram.get(key(model.programId(), model.sourceFile())));
    }

    /** All CFGs (input order). */
    public Collection<ControlFlowGraph> all() {
        return byProgram.values();
    }

    /** The key of a program as written, shared with {@code DataFlowFacts}. */
    public static String key(String programId, String sourceFile) {
        return programId + "\0" + sourceFile;
    }
}
