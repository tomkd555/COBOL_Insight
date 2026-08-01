package jp.cobolinsight.cli;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.json.JsonWriter;
import jp.cobolinsight.engineapi.pipeline.ExitCodes;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.persistence.PersistenceDao;
import jp.cobolinsight.persistence.PersistenceDatabase;
import jp.cobolinsight.persistence.model.CallEdgeRecord;
import jp.cobolinsight.persistence.model.FindingRecord;
import jp.cobolinsight.persistence.model.NodeRecord;
import jp.cobolinsight.persistence.model.SourceRecord;
import jp.cobolinsight.rules.sarif.SarifWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * `report` の中核処理。scan 済み SQLite を入力に、資産インベントリ・呼出関係の
 * 要約・scan 由来 finding を DB から読む。lint 検出(id が "R")と SQL 助言(id が "S")は scan が
 * 永続化しないため、DB と同じ資産フォルダに対し {@link LintRunner}・{@link SqlAdviseRunner} を
 * メモリ上で再実行して収集する。統合結果を HTML とテキストの両形式へ整形し、CI 向け終了コードを
 * {@link ExitCodes#fromFindings} で返す。
 */
public final class ReportRunner {

    /**
     * DB の FINDING 行のうち、この ID 未満は scan 由来(復号・パース失敗)、以上は呼出関係グラフ層
     * (linker 由来の解決根拠 finding)。{@link ScanRunner#GRAPH_ID_BASE} と一致させる。
     */
    private static final long GRAPH_ID_BASE = ScanRunner.GRAPH_ID_BASE;

    /** userRulesFile は lint 段へそのまま渡す。指定が無い(null)場合は組み込みだけを使う。 */
    public record Options(Path inputDir, Path databaseFile, List<Path> copybookSearchPaths,
            Map<String, String> codepageOverrides, Set<String> disabledRuleIds,
            Path userRulesFile) {

        public Options(Path inputDir, Path databaseFile, List<Path> copybookSearchPaths,
                Map<String, String> codepageOverrides, Set<String> disabledRuleIds) {
            this(inputDir, databaseFile, copybookSearchPaths, codepageOverrides, disabledRuleIds,
                    null);
        }
    }

    /** 資産インベントリの1件(SOURCE 表 + NODE 表の種別)。 */
    public record AssetEntry(String path, String type, String codepage, long byteSize) {
    }

    /** 呼出関係グラフの要約(ノード種別ごとの件数と、ラベルで解決した辺の一覧)。 */
    public record CallGraphSummary(int nodeCount, int edgeCount, Map<String, Integer> nodesByType,
            List<EdgeView> edges) {

        public CallGraphSummary {
            nodesByType = Map.copyOf(nodesByType);
            edges = List.copyOf(edges);
        }
    }

    /** 呼出関係グラフの辺1本(両端をノードラベルへ解決済み)。 */
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
        // SQLite は指定ファイルが無ければ新規作成する。存在検査を置かないと、パスの誤りが
        // 「資産0件のレポート」として通り、空のDBファイルだけが残る。
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

        LintRunner.Result lint = LintRunner.run(new LintRunner.Options(options.inputDir(),
                options.copybookSearchPaths(), options.codepageOverrides(),
                options.disabledRuleIds(), options.userRulesFile()));
        SqlAdviseRunner.Result advice = SqlAdviseRunner.run(new SqlAdviseRunner.Options(
                options.inputDir(), options.copybookSearchPaths(), options.codepageOverrides(),
                options.disabledRuleIds()));

        List<Finding> forExitCode = new ArrayList<>();
        forExitCode.addAll(scanFindings);
        forExitCode.addAll(lint.findings());
        forExitCode.addAll(advice.findings());
        int exitCode = ExitCodes.fromFindings(forExitCode);

        String html = ReportRenderer.toHtml(inventory, scanFindings, lint.findings(),
                advice.findings(), callGraph);
        String text = ReportRenderer.toText(inventory, scanFindings, lint.findings(),
                advice.findings(), callGraph, exitCode);
        return new Result(inventory, scanFindings, lint.findings(), advice.findings(),
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
     * DB の FINDING 表を Finding へ復元する(source_id をソースパスへ解決)。scan 由来(復号・パース
     * 失敗)と呼出関係グラフ層(linker 由来)の両方を含める。
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

    /** NODE・CALL_EDGE 表から呼出関係の要約(種別ごとのノード件数・ラベル解決済みの辺一覧)を作る。 */
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
