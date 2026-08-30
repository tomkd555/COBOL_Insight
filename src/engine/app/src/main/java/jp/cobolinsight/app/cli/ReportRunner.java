package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Persist;
import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.SourceSet;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.json.JsonWriter;
import jp.cobolinsight.core.pipeline.ExitCodes;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.CallEdgeRecord;
import jp.cobolinsight.app.persistence.model.FindingRecord;
import jp.cobolinsight.app.persistence.model.NodeRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import jp.cobolinsight.rules.RuleSet;
import jp.cobolinsight.rules.sarif.SarifWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Core processing of `report`. Takes an already-scanned SQLite database as input and reads the
 * asset inventory, call-graph summary, and scan-originated findings from the DB. Since scan does
 * not persist lint detections (id starting with "R") or SQL advice (id starting with "S"), these
 * are collected by re-running the report pipeline in memory against the same asset folder as the
 * DB. Formats the combined result into both HTML and text, and returns a CI exit code via
 * {@link ExitCodes#fromFindings}.
 */
public final class ReportRunner {

    /**
     * Of the FINDING rows in the DB, those with an ID below this value originate from scan
     * (decode/parse failures); those at or above it belong to the call-graph layer (linker-derived
     * resolution-basis findings). Kept in sync with {@link Persist#GRAPH_ID_BASE}.
     */
    private static final long GRAPH_ID_BASE = Persist.GRAPH_ID_BASE;

    public record Options(Path inputDir, Path databaseFile, List<Path> copybookSearchPaths,
            Map<String, String> codepageOverrides, RuleSet ruleSet) {

        /** The default rule set: every built-in rule, no configuration file. */
        public Options(Path inputDir, Path databaseFile, List<Path> copybookSearchPaths,
                Map<String, String> codepageOverrides) {
            this(inputDir, databaseFile, copybookSearchPaths, codepageOverrides,
                    RuleSet.load((Path) null));
        }
    }

    /** One entry of the asset inventory (SOURCE table + type from the NODE table). */
    public record AssetEntry(String path, String type, String codepage, long byteSize) {
    }

    /** Call-graph summary (a count per node type, plus the list of edges resolved to labels). */
    public record CallGraphSummary(int nodeCount, int edgeCount, Map<String, Integer> nodesByType,
            List<EdgeView> edges) {

        public CallGraphSummary {
            nodesByType = Map.copyOf(nodesByType);
            edges = List.copyOf(edges);
        }
    }

    /** One edge of the call graph (both endpoints already resolved to node labels). */
    public record EdgeView(String from, String to, String kind) {
    }

    public record Result(List<AssetEntry> inventory, List<Finding> scanFindings,
            List<Finding> lintFindings, List<Finding> sqlAdviceFindings,
            CallGraphSummary callGraph, String html, String text, int exitCode) {

        public Result {
            inventory = List.copyOf(inventory);
            scanFindings = List.copyOf(scanFindings);
            lintFindings = List.copyOf(lintFindings);
            sqlAdviceFindings = List.copyOf(sqlAdviceFindings);
        }

        public String summaryJson(String htmlPath, String textPath) {
            JsonWriter writer = new JsonWriter();
            writer.beginObject()
                    .name("assets").value(inventory.size())
                    .name("scanFindings").value(scanFindings.size())
                    .name("lintFindings").value(lintFindings.size())
                    .name("sqlAdvice").value(sqlAdviceFindings.size())
                    .name("callGraphNodes").value(callGraph.nodeCount())
                    .name("callGraphEdges").value(callGraph.edgeCount())
                    .name("htmlFile").value(htmlPath.replace('\\', '/'))
                    .name("textFile").value(textPath.replace('\\', '/'))
                    .name("exitCode").value(exitCode)
                    .endObject();
            return writer.toString();
        }
    }

    private ReportRunner() {
    }

    public static Result run(Options options) {
        // SQLite creates a new file if the specified one is missing. Without an existence check,
        // a mistaken path would silently pass as "a report with 0 assets", leaving only an empty DB file.
        if (!Files.isRegularFile(options.databaseFile())) {
            throw new IllegalStateException(
                    "SQLiteプロジェクトファイルが無い: " + options.databaseFile()
                            + "。先に scan を実行すること。");
        }
        List<AssetEntry> inventory;
        List<Finding> scanFindings;
        CallGraphSummary callGraph;
        try (PersistenceDatabase database = PersistenceDatabase.open(options.databaseFile())) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            Map<Long, SourceRecord> sourceById = new HashMap<>();
            Map<Long, String> typeBySourceId = new HashMap<>();
            inventory = readInventory(dao, sourceById, typeBySourceId);
            scanFindings = readScanFindings(dao, sourceById);
            callGraph = readCallGraph(dao);
        }

        // One pass over the assets serves both halves of the report: the bug detection and the
        // SQL advice see the same parse, and their findings stay in their own sections.
        SourceSet analysis = Pipelines.report(options.inputDir(), options.copybookSearchPaths(),
                options.codepageOverrides(), options.ruleSet());
        LintRunner.reportWarnings(analysis);
        List<Finding> lintFindings = new ArrayList<>(analysis.findings());
        lintFindings.addAll(analysis.ruleFindings(Command.REPORT));
        lintFindings.sort(SarifWriter.findingOrder());
        List<Finding> adviceFindings = List.copyOf(analysis.ruleFindings(Command.SQL_LINT));

        List<Finding> forExitCode = new ArrayList<>();
        forExitCode.addAll(scanFindings);
        forExitCode.addAll(lintFindings);
        forExitCode.addAll(adviceFindings);
        int exitCode = ExitCodes.fromFindings(forExitCode);

        String html = ReportRenderer.toHtml(inventory, scanFindings, lintFindings,
                adviceFindings, callGraph);
        String text = ReportRenderer.toText(inventory, scanFindings, lintFindings,
                adviceFindings, callGraph, exitCode);
        return new Result(inventory, scanFindings, lintFindings, adviceFindings,
                callGraph, html, text, exitCode);
    }

    private static List<AssetEntry> readInventory(PersistenceDao dao,
            Map<Long, SourceRecord> sourceById, Map<Long, String> typeBySourceId) {
        for (NodeRecord node : dao.findAllNodes()) {
            if (node.id() < GRAPH_ID_BASE) {
                typeBySourceId.put(node.id(), node.type());
            }
        }
        List<AssetEntry> inventory = new ArrayList<>();
        for (SourceRecord source : dao.findAllSources()) {
            sourceById.put(source.id(), source);
            inventory.add(new AssetEntry(source.path(),
                    typeBySourceId.getOrDefault(source.id(), "UNKNOWN"),
                    source.codepage() == null ? "?" : source.codepage(), source.byteSize()));
        }
        return inventory;
    }

    /**
     * Restores the DB's FINDING table into Finding objects (resolving source_id to the source
     * path). Includes both scan-originated (decode/parse failure) and call-graph-layer
     * (linker-derived) findings.
     */
    private static List<Finding> readScanFindings(PersistenceDao dao,
            Map<Long, SourceRecord> sourceById) {
        List<Finding> findings = new ArrayList<>();
        for (SourceRecord source : sourceById.values()) {
            for (FindingRecord record : dao.findFindingsBySource(source.id())) {
                findings.add(Finding.of(record.ruleId(), FindingLevel.valueOf(record.level()),
                        record.message(), new SourcePosition(source.path(),
                                Math.max(1, record.startLine()), Math.max(1, record.startCol()),
                                (int) Math.max(SourcePosition.UNKNOWN_BYTE_OFFSET,
                                        record.byteOffset()))));
            }
        }
        findings.sort(SarifWriter.findingOrder());
        return findings;
    }

    /** Builds a call-graph summary (node count per type, list of label-resolved edges) from the NODE and CALL_EDGE tables. */
    private static CallGraphSummary readCallGraph(PersistenceDao dao) {
        List<NodeRecord> nodes = dao.findAllNodes();
        Map<Long, String> labelById = new HashMap<>();
        Map<String, Integer> nodesByType = new TreeMap<>();
        for (NodeRecord node : nodes) {
            labelById.put(node.id(), node.label());
            nodesByType.merge(node.type(), 1, Integer::sum);
        }
        List<EdgeView> edges = new ArrayList<>();
        for (CallEdgeRecord edge : dao.findAllCallEdges()) {
            edges.add(new EdgeView(labelById.getOrDefault(edge.fromNode(), "?"),
                    labelById.getOrDefault(edge.toNode(), "?"), edge.kind()));
        }
        edges.sort(Comparator.comparing(EdgeView::from).thenComparing(EdgeView::to)
                .thenComparing(EdgeView::kind));
        return new CallGraphSummary(nodes.size(), edges.size(), nodesByType, edges);
    }
}
