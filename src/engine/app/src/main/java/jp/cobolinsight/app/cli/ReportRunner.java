package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Persist;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.json.JsonReader;
import jp.cobolinsight.core.json.JsonWriter;
import jp.cobolinsight.core.pipeline.ExitCodes;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.CallEdgeRecord;
import jp.cobolinsight.app.persistence.model.FindingRecord;
import jp.cobolinsight.app.persistence.model.NodeRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import jp.cobolinsight.rules.sarif.SarifWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Core processing of `report`. Takes an already-scanned SQLite database and the two SARIF files a
 * prior {@code lint} wrote as input: the DB supplies the asset inventory and the call-graph
 * summary, and the two SARIF files supply the lint findings (id starting with "R") and the SQL
 * advice (id starting with "S"), since scan does not persist either. Formats the combined result
 * into both HTML and text, and returns a CI exit code via {@link ExitCodes#fromFindings}.
 */
public final class ReportRunner {

    /**
     * Of the FINDING rows in the DB, those with an ID below this value originate from scan
     * (decode/parse failures); those at or above it belong to the call-graph layer (linker-derived
     * resolution-basis findings). Kept in sync with {@link Persist#GRAPH_ID_BASE}.
     */
    private static final long GRAPH_ID_BASE = Persist.GRAPH_ID_BASE;

    public record Options(Path databaseFile, Path sarifFile, Path sqlSarifFile) {
    }

    /** One entry of the asset inventory (SOURCE table + type from the NODE table). */
    public record AssetEntry(String path, String type, String codepage, long byteSize) {
    }

    /** Call-graph summary (a count per node type, plus the list of edges resolved to labels). */
    public record CallGraphSummary(int nodeCount, int edgeCount, Map<String, Integer> nodesByType,
            List<EdgeView> edges) {

        public CallGraphSummary {
            // A sorted copy: Map.copyOf iterates in a per-JVM random order, which made the
            // rendered node-type list differ between runs.
            nodesByType = Collections.unmodifiableMap(new TreeMap<>(nodesByType));
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

    /**
     * Assembles the report. A missing {@code options.databaseFile()} is refused: SQLite would
     * create a new file, and a mistaken path would silently pass as "a report with 0 assets",
     * leaving only an empty DB file. A missing SARIF file is refused the same way, pointing at
     * {@code lint} instead. The Japanese message reaches stderr through the execution exception
     * handler in {@code Main}.
     */
    public static Result run(Options options) {
        if (!Files.isRegularFile(options.databaseFile())) {
            throw new IllegalStateException(
                    options.databaseFile() + " がありません。先に scan を実行してください。");
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

        SarifRun lint = readSarifRun(options.sarifFile());
        List<Finding> lintFindings = lint.findings();
        List<Finding> adviceFindings = readSarifRun(options.sqlSarifFile()).findings();

        List<Finding> forExitCode = new ArrayList<>();
        forExitCode.addAll(scanFindings);
        forExitCode.addAll(lintFindings);
        forExitCode.addAll(adviceFindings);
        int exitCode = ExitCodes.fromFindings(forExitCode);

        String html = ReportRenderer.toHtml(inventory, scanFindings, lintFindings,
                adviceFindings, callGraph, lint.scope());
        String text = ReportRenderer.toText(inventory, scanFindings, lintFindings,
                adviceFindings, callGraph, exitCode, lint.scope());
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
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
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

    /** One SARIF run: its results, and the scopes the {@code lint} that wrote it was given. */
    private record SarifRun(List<Finding> findings, List<String> scope) {
    }

    /**
     * Reads a SARIF 2.1.0 file's {@code runs[0]} — its results as Finding objects, and the scopes
     * its {@code properties} names, which a whole-folder run leaves out. A missing or non-regular
     * file is refused the same way as a missing database, pointing at {@code lint}.
     */
    private static SarifRun readSarifRun(Path sarifFile) {
        if (!Files.isRegularFile(sarifFile)) {
            throw new IllegalStateException(
                    sarifFile + " がありません。先に lint を実行してください。");
        }
        String text;
        try {
            text = Files.readString(sarifFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> root = JsonReader.asObject(JsonReader.parse(text));
        List<Object> runs = JsonReader.asArray(root.get("runs"));
        Map<String, Object> run = JsonReader.asObject(runs.get(0));
        List<Object> results = JsonReader.asArray(run.get("results"));

        List<Finding> findings = new ArrayList<>();
        for (Object element : results) {
            Map<String, Object> result = JsonReader.asObject(element);
            String ruleId = (String) result.get("ruleId");
            FindingLevel level = FindingLevel.valueOf(
                    ((String) result.get("level")).toUpperCase(Locale.ROOT));
            String message = (String) JsonReader.asObject(result.get("message")).get("text");
            Map<String, Object> location =
                    JsonReader.asObject(JsonReader.asArray(result.get("locations")).get(0));
            Map<String, Object> physicalLocation =
                    JsonReader.asObject(location.get("physicalLocation"));
            String uri = (String) JsonReader.asObject(physicalLocation.get("artifactLocation"))
                    .get("uri");
            Map<String, Object> region = JsonReader.asObject(physicalLocation.get("region"));
            int startLine = ((Number) region.get("startLine")).intValue();
            int startColumn = ((Number) region.get("startColumn")).intValue();
            findings.add(Finding.of(ruleId, level, message, new SourcePosition(decodeUri(uri),
                    startLine, startColumn, SourcePosition.UNKNOWN_BYTE_OFFSET)));
        }
        findings.sort(SarifWriter.findingOrder());
        return new SarifRun(findings, scopeOf(run));
    }

    /** The scopes of {@code runs[0].properties.scope}; empty for a run over the whole folder. */
    private static List<String> scopeOf(Map<String, Object> run) {
        Object properties = run.get("properties");
        if (properties == null) {
            return List.of();
        }
        Object scope = JsonReader.asObject(properties).get("scope");
        if (scope == null) {
            return List.of();
        }
        return JsonReader.asArray(scope).stream().map(String::valueOf).toList();
    }

    /** The inverse of SarifWriter's percent-encoding: decodes each '/'-separated URI segment as UTF-8. */
    private static String decodeUri(String uri) {
        String[] segments = uri.split("/", -1);
        StringBuilder out = new StringBuilder(uri.length());
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(decodeSegment(segments[i]));
        }
        return out.toString();
    }

    private static String decodeSegment(String segment) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '%' && i + 2 < segment.length()) {
                bytes.write(Integer.parseInt(segment.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                bytes.write(c);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
