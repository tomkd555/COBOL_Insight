package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.ScanOutcome;
import jp.cobolinsight.app.pipeline.Persist;
import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.callgraph.CallGraphNode;
import jp.cobolinsight.core.callgraph.EdgeKind;
import jp.cobolinsight.core.callgraph.NodeKind;
import jp.cobolinsight.core.callgraph.Resolution;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.analysis.linker.CallGraphLinker;
import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.CallEdgeRecord;
import jp.cobolinsight.app.persistence.model.FindingRecord;
import jp.cobolinsight.app.persistence.model.NodeRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance regression test for call-graph construction. Compares the call graph built from
 * the entire samples/ set (COBOL9 / JCL3 / BMS1) against the expected graph in
 * expected-results.md chapters 4, 5 and 9, edge by edge.
 */
class CallGraphSamplesAcceptanceTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    @TempDir
    static Path tempDir;

    private static ScanOutcome result;
    private static PersistenceDatabase database;
    private static PersistenceDao dao;

    @BeforeAll
    static void scanSamples() {
        Path databaseFile = tempDir.resolve("m2.db");
        result = Pipelines.scan(SAMPLES, databaseFile,
                List.of(SAMPLES.resolve("copybook")), Map.of());
        database = PersistenceDatabase.open(databaseFile);
        dao = new PersistenceDao(database.connection());
    }

    @AfterAll
    static void closeDatabase() {
        database.close();
    }

    private static String edgeKey(CallGraphEdge edge) {
        return edge.fromId() + " -> " + edge.toId() + " [" + edge.kind() + "/"
                + edge.resolution() + "]";
    }

    /** All expected edges derived from expected-results.md chapter 4 (JCL/dataset), chapter 5 (CALL) and chapter 9 (CICS). */
    private static Set<String> expectedEdges() {
        Set<String> expected = new TreeSet<>();
        // Chapter 4: job -> step -> program (matches EXEC PGM=. SYKD020's STEP020 expands
        // to STEP020.STEP020 because it runs via the in-stream PROC SYKPRC01)
        expected.add("job:SYKD010 -> step:SYKD010.STEP010 [EXECUTION/CONSTANT]");
        expected.add("job:SYKD010 -> step:SYKD010.STEP020 [EXECUTION/CONSTANT]");
        expected.add("job:SYKD020 -> step:SYKD020.STEP010 [EXECUTION/CONSTANT]");
        expected.add("job:SYKD020 -> step:SYKD020.STEP020.STEP020 [EXECUTION/CONSTANT]");
        expected.add("job:SYKD030 -> step:SYKD030.STEP010 [EXECUTION/CONSTANT]");
        expected.add("job:SYKD030 -> step:SYKD030.STEP020 [EXECUTION/CONSTANT]");
        expected.add("step:SYKD010.STEP010 -> program:SYK001 [EXECUTION/CONSTANT]");
        expected.add("step:SYKD010.STEP020 -> program:SYK002 [EXECUTION/CONSTANT]");
        expected.add("step:SYKD020.STEP010 -> program:SYK006 [EXECUTION/CONSTANT]");
        expected.add("step:SYKD020.STEP020.STEP020 -> program:SYK007 [EXECUTION/CONSTANT]");
        expected.add("step:SYKD030.STEP010 -> program:SYK001 [EXECUTION/CONSTANT]");
        expected.add("step:SYKD030.STEP020 -> program:SYK002 [EXECUTION/CONSTANT]");
        // Chapter 4: dataset references. &CYCLE (SET CYCLE=250718) must already be resolved to 250718.
        // Inter-step linkage (ORDER.VALID / STOCK.EXTRACT), inter-job linkage (ORDER.ERROR),
        // and the shared VSAM (SYKV.ORDER.MASTER) appear as sharing of the same dataset node.
        expected.add("step:SYKD010.STEP010 -> dataset:SYKT.D250718.ORDER.DAILY [REFERENCE/CONSTANT]");
        expected.add("step:SYKD010.STEP010 -> dataset:SYKW.D250718.ORDER.VALID [REFERENCE/CONSTANT]");
        expected.add("step:SYKD010.STEP010 -> dataset:SYKW.D250718.ORDER.ERROR [REFERENCE/CONSTANT]");
        expected.add("step:SYKD010.STEP020 -> dataset:SYKW.D250718.ORDER.VALID [REFERENCE/CONSTANT]");
        expected.add("step:SYKD010.STEP020 -> dataset:SYKV.ORDER.MASTER [REFERENCE/CONSTANT]");
        expected.add("step:SYKD020.STEP010 -> dataset:SYKT.D250718.STOCK.DAILY [REFERENCE/CONSTANT]");
        expected.add("step:SYKD020.STEP010 -> dataset:SYKW.D250718.STOCK.EXTRACT [REFERENCE/CONSTANT]");
        expected.add(
                "step:SYKD020.STEP020.STEP020 -> dataset:SYKW.D250718.STOCK.EXTRACT [REFERENCE/CONSTANT]");
        expected.add("step:SYKD030.STEP010 -> dataset:SYKW.D250718.ORDER.ERROR [REFERENCE/CONSTANT]");
        expected.add(
                "step:SYKD030.STEP010 -> dataset:SYKW.D250718.ORDER.RERUN.VALID [REFERENCE/CONSTANT]");
        expected.add(
                "step:SYKD030.STEP010 -> dataset:SYKW.D250718.ORDER.RERUN.ERROR [REFERENCE/CONSTANT]");
        expected.add(
                "step:SYKD030.STEP020 -> dataset:SYKW.D250718.ORDER.RERUN.VALID [REFERENCE/CONSTANT]");
        expected.add("step:SYKD030.STEP020 -> dataset:SYKV.ORDER.MASTER [REFERENCE/CONSTANT]");
        // Chapter 4: Db2 table references (SYK006 -> SYKDB.ZAIKOM; SYK007 -> SYKDB.ZAIKOM and SYKDB.SOKOM)
        expected.add("program:SYK006 -> db2:SYKDB.ZAIKOM [REFERENCE/CONSTANT]");
        expected.add("program:SYK007 -> db2:SYKDB.ZAIKOM [REFERENCE/CONSTANT]");
        expected.add("program:SYK007 -> db2:SYKDB.SOKOM [REFERENCE/CONSTANT]");
        // Chapter 5: static CALL (SYK001->SYK003; SYK006->SYK005 collapses two occurrences at
        // lines 133 and 142 into one edge); dynamic CALL (SYK002->SYK004, resolved by constant
        // propagation, so its origin is CONSTANT)
        expected.add("program:SYK001 -> program:SYK003 [CALL/CONSTANT]");
        expected.add("program:SYK002 -> program:SYK004 [CALL/CONSTANT]");
        expected.add("program:SYK006 -> program:SYK005 [CALL/CONSTANT]");
        // Chapter 9: CICS transitions (XCTL, RETURN TRANSID, SYK8->SYK008 resolution via the definition table) and map references
        expected.add("program:SYK008 -> program:SYK009 [TRANSACTION_TRANSITION/CONSTANT]");
        expected.add("program:SYK008 -> transaction:SYK8 [TRANSACTION_TRANSITION/CONSTANT]");
        expected.add("transaction:SYK8 -> program:SYK008 [TRANSACTION_TRANSITION/CONSTANT]");
        expected.add("program:SYK008 -> bmsmap:SYKMAP1.SYKM01 [MAP_REFERENCE/CONSTANT]");
        expected.add("program:SYK008 -> bmsmap:SYKMAP1.SYKM99 [MAP_REFERENCE/CONSTANT]");
        return expected;
    }

    @Test
    void graphMatchesExpectedResultEdgeByEdge() {
        Set<String> actual = result.callGraph().edges().stream()
                .map(CallGraphSamplesAcceptanceTest::edgeKey)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(expectedEdges(), actual, "expected-results.md 4・5・9章の正解グラフと1辺単位で一致すること");
    }

    @Test
    void nodesAreTypedAsExpected() {
        Map<String, CallGraphNode> byId = result.callGraph().nodes().stream()
                .collect(Collectors.toMap(CallGraphNode::id, n -> n));
        assertEquals(32, byId.size(),
                "ジョブ3・ステップ6・プログラム10(SYK001〜009とSYKENC1)・データセット8・Db2表2・"
                        + "トランザクション1・BMSマップ2");
        assertEquals(NodeKind.JOB, byId.get("job:SYKD010").kind());
        assertEquals(NodeKind.STEP, byId.get("step:SYKD020.STEP020.STEP020").kind());
        assertEquals(NodeKind.PROGRAM, byId.get("program:SYK004").kind());
        assertEquals(NodeKind.DATASET, byId.get("dataset:SYKV.ORDER.MASTER").kind());
        assertEquals(NodeKind.DB2_TABLE, byId.get("db2:SYKDB.SOKOM").kind());
        assertEquals(NodeKind.TRANSACTION, byId.get("transaction:SYK8").kind());
        assertEquals(NodeKind.BMS_MAP, byId.get("bmsmap:SYKMAP1.SYKM01").kind());
        // samples/ has no unresolved dynamic CALL or external utility (those are covered by
        // synthetic fixtures in the linker module tests)
        assertTrue(byId.values().stream().noneMatch(n -> n.kind() == NodeKind.UNRESOLVED));
        assertTrue(byId.values().stream().noneMatch(n -> n.kind() == NodeKind.EXTERNAL_UTILITY));
        // All 9 COBOL programs must be source-derived program nodes, not typed as external
        for (int i = 1; i <= 9; i++) {
            assertTrue(byId.get("program:SYK00" + i).attributes().isEmpty(),
                    "SYK00" + i + " は外部プログラム扱いにならないこと");
        }
    }

    @Test
    void dynamicCallResolutionIsRecordedAsConstantOriginFinding() {
        List<Finding> findings = result.linkerFindings();
        assertEquals(1, findings.size(), "linker findingsは動的CALL解決の記録1件のみ: " + findings);
        Finding finding = findings.get(0);
        assertEquals(CallGraphLinker.DYNAMIC_CALL_RESOLVED_RULE_ID, finding.ruleId());
        assertEquals(FindingLevel.NOTE, finding.level());
        assertEquals(113, finding.location().line(), "SYK002.cbl 113行目のCALL文を指すこと");
        assertTrue(finding.message().contains("定数由来"), finding.message());
        assertTrue(finding.message().contains("WS-PROG-NAME"), finding.message());
        assertTrue(finding.message().contains("SYK004"), finding.message());
    }

    @Test
    void graphLayerIsPersistedToNodeAndCallEdgeTables() {
        Map<String, Long> sourceIdByPath = dao.findAllSources().stream()
                .collect(Collectors.toMap(SourceRecord::path, SourceRecord::id));

        // Graph-layer nodes: the 19 nodes with no source (6 steps, 8 datasets, 2 Db2 tables,
        // 1 transaction, 2 BMS maps) must be persisted following the ID numbering convention
        long graphNodes = result.callGraph().nodes().stream()
                .filter(n -> n.kind() != NodeKind.PROGRAM && n.kind() != NodeKind.JOB).count();
        assertEquals(19, graphNodes);
        for (long i = 0; i < graphNodes; i++) {
            NodeRecord node = dao.findNode(Persist.GRAPH_ID_BASE + i).orElseThrow();
            assertTrue(Set.of("STEP", "DATASET", "DB2_TABLE", "TRANSACTION", "BMS_MAP")
                    .contains(node.type()), node.type());
        }

        // Graph-layer edges: all 36 edges must be persisted, and the dynamic CALL edge must carry the variable name in host_var
        long syk002 = sourceIdByPath.get("cobol/SYK002.cbl");
        long syk004 = sourceIdByPath.get("cobol/SYK004.cbl");
        int graphEdgeCount = 0;
        boolean dynamicEdgeFound = false;
        for (int i = 0; i < result.callGraph().edges().size(); i++) {
            CallEdgeRecord edge = dao.findCallEdge(Persist.GRAPH_ID_BASE + i).orElseThrow();
            graphEdgeCount++;
            if (edge.fromNode() == syk002 && edge.toNode() == syk004
                    && "CALL".equals(edge.kind())) {
                assertEquals("CONSTANT", edge.resolution());
                assertEquals("WS-PROG-NAME", edge.hostVar());
                dynamicEdgeFound = true;
            }
        }
        assertEquals(36, graphEdgeCount);
        assertTrue(dynamicEdgeFound, "動的CALL辺がNODE.id=SOURCE.id規約のノードIDで保存されること");

        // linker finding: a NOTE tied to SYK002's source ID must be persisted
        FindingRecord findingRecord = dao.findFinding(Persist.GRAPH_ID_BASE).orElseThrow();
        assertEquals(CallGraphLinker.DYNAMIC_CALL_RESOLVED_RULE_ID, findingRecord.ruleId());
        assertEquals("NOTE", findingRecord.level());
        assertEquals(syk002, findingRecord.sourceId());
    }

    @Test
    void m1CopyAndExecutionEdgesRemainIntact() {
        Map<Long, String> pathById = dao.findAllSources().stream()
                .collect(Collectors.toMap(SourceRecord::id, SourceRecord::path));
        Set<String> executionEdges = new TreeSet<>();
        Set<String> copyEdges = new TreeSet<>();
        for (SourceRecord source : dao.findAllSources()) {
            for (CallEdgeRecord edge : dao.findEdgesFrom(source.id())) {
                if (edge.id() >= Persist.GRAPH_ID_BASE) {
                    continue;
                }
                String key = pathById.get(edge.fromNode()) + "->" + pathById.get(edge.toNode());
                if ("EXECUTION".equals(edge.kind())) {
                    executionEdges.add(key);
                } else if ("COPY".equals(edge.kind())) {
                    copyEdges.add(key);
                }
            }
        }
        assertEquals(6, executionEdges.size(), "M1のJCL→プログラム実行辺6本が残ること");
        assertEquals(6, copyEdges.size(), "M1のコピー句取込辺6本が残ること");
    }

    @Test
    void rescanRebuildsGraphLayerWithoutDuplicationAndDeterministically() throws IOException {
        // Other tests reference the shared DB (m2.db), so the rescan writes to a dedicated copy
        Path rescanDb = tempDir.resolve("m2-rescan.db");
        Files.copy(tempDir.resolve("m2.db"), rescanDb);
        ScanOutcome second = Pipelines.scan(SAMPLES,
                rescanDb, List.of(SAMPLES.resolve("copybook")), Map.of());
        assertEquals(List.of(), second.summary().analyzed(), "変更が無ければ再解析しないこと");
        assertEquals(20, second.summary().skipped().size());
        assertEquals(0, second.summary().exitCode());
        assertEquals(result.callGraph().toJson(), second.callGraph().toJson(),
                "増分scan(全ファイルskip)でも同一のグラフが再構築されること");

        try (PersistenceDatabase reopened = PersistenceDatabase.open(rescanDb)) {
            PersistenceDao dao2 = new PersistenceDao(reopened.connection());
            for (int i = 0; i < result.callGraph().edges().size(); i++) {
                assertTrue(dao2.findCallEdge(Persist.GRAPH_ID_BASE + i).isPresent());
            }
            assertTrue(dao2.findCallEdge(
                            Persist.GRAPH_ID_BASE + result.callGraph().edges().size()).isEmpty(),
                    "グラフ層エッジが重複蓄積しないこと");
        }
    }

    @Test
    void separateDatabaseYieldsIdenticalGraphJsonAndDot() {
        ScanOutcome second = Pipelines.scan(SAMPLES,
                tempDir.resolve("m2-second.db"), List.of(SAMPLES.resolve("copybook")), Map.of());
        assertEquals(result.callGraph().toJson(), second.callGraph().toJson(),
                "同一入力からのJSONがバイト単位で一致すること(決定論)");
        assertEquals(result.callGraph().toDot(), second.callGraph().toDot(),
                "同一入力からのDOTがバイト単位で一致すること(決定論)");
    }
}
