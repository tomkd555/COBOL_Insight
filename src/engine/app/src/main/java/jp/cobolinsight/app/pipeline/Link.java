package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.analysis.linker.CallGraphLinker;
import jp.cobolinsight.analysis.linker.LinkResult;
import jp.cobolinsight.analysis.linker.LinkerInput;
import jp.cobolinsight.core.callgraph.CallGraph;
import jp.cobolinsight.core.callgraph.CallGraphNode;
import jp.cobolinsight.core.callgraph.NodeKind;
import jp.cobolinsight.core.rule.Needs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the call graph that spans jobs, programs, copybooks and maps.
 *
 * <p>Assets that could not be analysed are added as isolated nodes. Dropping them would leave a
 * picture that reads as the whole estate while quietly omitting the parts nobody could read.
 */
public final class Link implements Step {

    /** Node ID prefix for an unanalysable asset; with the relative path it collides with nothing. */
    static final String UNANALYZABLE_NODE_ID_PREFIX = "unanalyzable:";

    @Override
    public void apply(SourceSet s) {
        if (!s.requires(Needs.CALL_GRAPH)) {
            return;
        }
        LinkResult linked = CallGraphLinker.link(new LinkerInput(s.programs(), s.jobs(),
                s.mapsets(), s.sqlByProgramId(),
                readTransactionTable(s.discovery().transactionTables())));
        LinkResult result = new LinkResult(withUnanalyzableNodes(linked.graph(), s.unanalyzable()),
                linked.findings(), linked.dynamicCallVariables());
        s.callGraph(result.graph());
        s.artifact(LinkResult.class, result);
    }

    private static CallGraph withUnanalyzableNodes(CallGraph graph, Map<String, String> reasons) {
        if (reasons.isEmpty()) {
            return graph;
        }
        List<CallGraphNode> nodes = new ArrayList<>(graph.nodes());
        for (Map.Entry<String, String> entry : reasons.entrySet()) {
            String relPath = entry.getKey();
            nodes.add(new CallGraphNode(UNANALYZABLE_NODE_ID_PREFIX + relPath,
                    NodeKind.UNANALYZABLE, relPath.substring(relPath.lastIndexOf('/') + 1),
                    Map.of("path", relPath, "reason", entry.getValue())));
        }
        return new CallGraph(nodes, graph.edges());
    }

    /**
     * Reads the CICS transaction table (transaction ID, program name). The first line is always a
     * header; later lines whose fields do not look like member names are skipped, as is a CSV that
     * will not decode. Where the file sits does not matter — the walk decided it was a table.
     */
    private static Map<String, String> readTransactionTable(List<Path> transactionTables) {
        Map<String, String> table = new TreeMap<>();
        for (Path csv : transactionTables) {
            List<String> lines;
            try {
                lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("警告: トランザクション定義表を復号できないため読み飛ばす: "
                        + csv + " (" + e + ")");
                continue;
            }
            for (String line : lines.stream().skip(1).toList()) {
                String[] fields = line.split(",");
                if (fields.length != 2) {
                    continue;
                }
                String transId = fields[0].trim();
                String program = fields[1].trim();
                if (SourceDiscovery.MEMBER_NAME_PATTERN.matcher(transId).matches()
                        && SourceDiscovery.MEMBER_NAME_PATTERN.matcher(program).matches()) {
                    table.put(transId, program);
                }
            }
        }
        return table;
    }
}
