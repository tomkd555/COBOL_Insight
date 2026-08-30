package jp.cobolinsight.analysis.linker;

import jp.cobolinsight.core.callgraph.CallGraph;
import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.finding.Finding;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Output of the linker. findings records resolution bases and unresolved cases (ordered by
 * file, line, rule ID); dynamicCallVariables is the set of designated variable names (in
 * dictionary order) for edges originating from a dynamic CALL. If multiple variables resolve to
 * the same edge, all their names are kept.
 */
public record LinkResult(CallGraph graph, List<Finding> findings,
        Map<CallGraphEdge, Set<String>> dynamicCallVariables) {

    public LinkResult {
        Objects.requireNonNull(graph, "graph");
        findings = List.copyOf(findings);
        dynamicCallVariables = Map.copyOf(dynamicCallVariables);
    }
}
