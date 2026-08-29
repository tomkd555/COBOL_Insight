package jp.cobolinsight.analysis.linker;

import jp.cobolinsight.core.callgraph.CallGraph;
import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.finding.Finding;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * linker の出力。findings は解決根拠・未解決の記録(ファイル・行・ルールID順)、
 * dynamicCallVariables は動的CALL由来の辺に対する指定変数名の集合(辞書順)である。
 * 同一辺へ複数の変数が解決した場合、全変数名を保持する。
 */
public record LinkResult(CallGraph graph, List<Finding> findings,
        Map<CallGraphEdge, Set<String>> dynamicCallVariables) {

    public LinkResult {
        Objects.requireNonNull(graph, "graph");
        findings = List.copyOf(findings);
        dynamicCallVariables = Map.copyOf(dynamicCallVariables);
    }
}
