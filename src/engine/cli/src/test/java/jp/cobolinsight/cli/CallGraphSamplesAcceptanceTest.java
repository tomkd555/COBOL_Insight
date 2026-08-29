package jp.cobolinsight.cli;

import jp.cobolinsight.engineapi.callgraph.CallGraphEdge;
import jp.cobolinsight.engineapi.callgraph.CallGraphNode;
import jp.cobolinsight.engineapi.callgraph.EdgeKind;
import jp.cobolinsight.engineapi.callgraph.NodeKind;
import jp.cobolinsight.engineapi.callgraph.Resolution;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.linker.CallGraphLinker;
import jp.cobolinsight.persistence.PersistenceDao;
import jp.cobolinsight.persistence.PersistenceDatabase;
import jp.cobolinsight.persistence.model.CallEdgeRecord;
import jp.cobolinsight.persistence.model.FindingRecord;
import jp.cobolinsight.persistence.model.NodeRecord;
import jp.cobolinsight.persistence.model.SourceRecord;
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
 * 呼出関係グラフ構築の受入回帰テスト。samples/ 全体(COBOL9・JCL3・BMS1)から構築した
 * 呼出関係グラフを、expected-results.md 4・5・9章の正解グラフと1辺単位で突合する。
 */
class CallGraphSamplesAcceptanceTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    @TempDir
    static Path tempDir;

    private static ScanRunner.Result result;
    private static PersistenceDatabase database;
    private static PersistenceDao dao;

    @BeforeAll
    static void scanSamples() {
        Path databaseFile = tempDir.resolve("m2.db");
        result = ScanRunner.runWithGraph(new ScanRunner.Options(SAMPLES, databaseFile,
                List.of(SAMPLES.resolve("copybook")), Map.of()));
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

    /** expected-results.md 4章(JCL・データセット)・5章(CALL)・9章(CICS)から起こした正解の全辺。 */
    private static Set<String> expectedEdges() {
        Set<String> expected = new TreeSet<>();
        // 4章: ジョブ→ステップ→プログラム(EXEC PGM=対応。SYKD020のSTEP020は
        // インストリームPROC SYKPRC01経由のためSTEP020.STEP020へ展開される)
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
        // 4章: データセット参照。&CYCLE(SET CYCLE=250718)は250718へ解決済みであること。
        // ステップ間連携(ORDER.VALID・STOCK.EXTRACT)、ジョブ間連携(ORDER.ERROR)、
        // 共有VSAM(SYKV.ORDER.MASTER)は同一データセットノードの共有として表れる。
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
        // 4章: Db2表参照(SYK006はSYKDB.ZAIKOM、SYK007はSYKDB.ZAIKOMとSYKDB.SOKOM)
        expected.add("program:SYK006 -> db2:SYKDB.ZAIKOM [REFERENCE/CONSTANT]");
        expected.add("program:SYK007 -> db2:SYKDB.ZAIKOM [REFERENCE/CONSTANT]");
        expected.add("program:SYK007 -> db2:SYKDB.SOKOM [REFERENCE/CONSTANT]");
        // 5章: 静的CALL(SYK001→SYK003、SYK006→SYK005は133・142行目の2回で1辺)・
        // 動的CALL(SYK002→SYK004。定数伝播による解決で定数由来)
        expected.add("program:SYK001 -> program:SYK003 [CALL/CONSTANT]");
        expected.add("program:SYK002 -> program:SYK004 [CALL/CONSTANT]");
        expected.add("program:SYK006 -> program:SYK005 [CALL/CONSTANT]");
        // 9章: CICS遷移(XCTL・RETURN TRANSID・定義表によるSYK8→SYK008解決)とマップ参照
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
        // samples/には未解決動的CALL・外部ユーティリティが無い(合成fixtureはlinkerモジュールで検証)
        assertTrue(byId.values().stream().noneMatch(n -> n.kind() == NodeKind.UNRESOLVED));
        assertTrue(byId.values().stream().noneMatch(n -> n.kind() == NodeKind.EXTERNAL_UTILITY));
        // 9本全てのCOBOLがソース由来のプログラムノードであり、外部型付けされないこと
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

        // グラフ層ノード: ソース非対応の19ノード(ステップ6・データセット8・Db2表2・
        // トランザクション1・BMSマップ2)が採番規約どおり保存されること
        long graphNodes = result.callGraph().nodes().stream()
                .filter(n -> n.kind() != NodeKind.PROGRAM && n.kind() != NodeKind.JOB).count();
        assertEquals(19, graphNodes);
        for (long i = 0; i < graphNodes; i++) {
            NodeRecord node = dao.findNode(ScanRunner.GRAPH_ID_BASE + i).orElseThrow();
            assertTrue(Set.of("STEP", "DATASET", "DB2_TABLE", "TRANSACTION", "BMS_MAP")
                    .contains(node.type()), node.type());
        }

        // グラフ層エッジ: 全36辺が保存され、動的CALL辺はhost_varに変数名を持つこと
        long syk002 = sourceIdByPath.get("cobol/SYK002.cbl");
        long syk004 = sourceIdByPath.get("cobol/SYK004.cbl");
        int graphEdgeCount = 0;
        boolean dynamicEdgeFound = false;
        for (int i = 0; i < result.callGraph().edges().size(); i++) {
            CallEdgeRecord edge = dao.findCallEdge(ScanRunner.GRAPH_ID_BASE + i).orElseThrow();
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

        // linker finding: SYK002のソースIDに紐づくNOTEが保存されること
        FindingRecord findingRecord = dao.findFinding(ScanRunner.GRAPH_ID_BASE).orElseThrow();
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
                if (edge.id() >= ScanRunner.GRAPH_ID_BASE) {
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
        // 共有DB(m2.db)は他テストが参照するため、rescanは専用の複製へ書き込む
        Path rescanDb = tempDir.resolve("m2-rescan.db");
        Files.copy(tempDir.resolve("m2.db"), rescanDb);
        ScanRunner.Result second = ScanRunner.runWithGraph(new ScanRunner.Options(SAMPLES,
                rescanDb, List.of(SAMPLES.resolve("copybook")), Map.of()));
        assertEquals(List.of(), second.summary().analyzed(), "変更が無ければ再解析しないこと");
        assertEquals(20, second.summary().skipped().size());
        assertEquals(0, second.summary().exitCode());
        assertEquals(result.callGraph().toJson(), second.callGraph().toJson(),
                "増分scan(全ファイルskip)でも同一のグラフが再構築されること");

        try (PersistenceDatabase reopened = PersistenceDatabase.open(rescanDb)) {
            PersistenceDao dao2 = new PersistenceDao(reopened.connection());
            for (int i = 0; i < result.callGraph().edges().size(); i++) {
                assertTrue(dao2.findCallEdge(ScanRunner.GRAPH_ID_BASE + i).isPresent());
            }
            assertTrue(dao2.findCallEdge(
                            ScanRunner.GRAPH_ID_BASE + result.callGraph().edges().size()).isEmpty(),
                    "グラフ層エッジが重複蓄積しないこと");
        }
    }

    @Test
    void separateDatabaseYieldsIdenticalGraphJsonAndDot() {
        ScanRunner.Result second = ScanRunner.runWithGraph(new ScanRunner.Options(SAMPLES,
                tempDir.resolve("m2-second.db"), List.of(SAMPLES.resolve("copybook")), Map.of()));
        assertEquals(result.callGraph().toJson(), second.callGraph().toJson(),
                "同一入力からのJSONがバイト単位で一致すること(決定論)");
        assertEquals(result.callGraph().toDot(), second.callGraph().toDot(),
                "同一入力からのDOTがバイト単位で一致すること(決定論)");
    }
}
