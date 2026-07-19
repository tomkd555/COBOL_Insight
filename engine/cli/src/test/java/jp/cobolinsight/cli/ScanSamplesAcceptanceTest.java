package jp.cobolinsight.cli;

import jp.cobolinsight.persistence.PersistenceDao;
import jp.cobolinsight.persistence.PersistenceDatabase;
import jp.cobolinsight.persistence.model.CallEdgeRecord;
import jp.cobolinsight.persistence.model.SourceRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1受入回帰テスト(05_開発計画.md §3.1)。samples/ 全体の scan が成功し、SQLiteへ期待どおりの
 * 行が入ることを、期待結果.md 4・6・9章の値と突合して検証する。
 */
class ScanSamplesAcceptanceTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

    @TempDir
    static Path tempDir;

    private static ScanRunner.Summary summary;
    private static PersistenceDatabase database;
    private static PersistenceDao dao;

    @BeforeAll
    static void scanSamples() {
        Path databaseFile = tempDir.resolve("m1.db");
        summary = ScanRunner.run(new ScanRunner.Options(SAMPLES, databaseFile,
                List.of(SAMPLES.resolve("copybook")), Map.of()));
        database = PersistenceDatabase.open(databaseFile);
        dao = new PersistenceDao(database.connection());
    }

    @AfterAll
    static void closeDatabase() {
        database.close();
    }

    private static Map<String, Long> sourceIdByPath() {
        return dao.findAllSources().stream()
                .collect(Collectors.toMap(SourceRecord::path, SourceRecord::id,
                        (a, b) -> a, LinkedHashMap::new));
    }

    @Test
    void allSixteenSourcesAreScannedWithoutError() {
        assertEquals(0, summary.exitCode(), "終了コードは成功(0)であること");
        assertEquals(0, summary.findingCount(), "パース失敗のfindingが無いこと");
        assertEquals(16, summary.analyzed().size(),
                "JCL3本・COBOL9本・コピー句3本・BMSマップ1本の計16本を解析すること");
        assertEquals(16, dao.findAllSources().size());
    }

    @Test
    void sourceIdsFollowLexicographicPathOrder() {
        List<SourceRecord> sources = dao.findAllSources();
        List<String> pathsById = sources.stream()
                .sorted(Comparator.comparingLong(SourceRecord::id))
                .map(SourceRecord::path).toList();
        List<String> sortedPaths = pathsById.stream().sorted().toList();
        assertEquals(sortedPaths, pathsById, "SOURCE.id はパスの辞書順で振られること");
        assertEquals(1, sources.stream().mapToLong(SourceRecord::id).min().orElseThrow());
        assertEquals(16, sources.stream().mapToLong(SourceRecord::id).max().orElseThrow());
    }

    @Test
    void nodesShareSourceIdsAndCobolProgramsArePersisted() throws SQLException {
        Map<String, Long> ids = sourceIdByPath();
        for (int i = 1; i <= 9; i++) {
            String program = "SYK00" + i;
            long sourceId = ids.get("cobol/" + program + ".cbl");
            assertEquals(program, dao.findProgram(sourceId).orElseThrow().programIdName());
            var node = dao.findNode(sourceId).orElseThrow();
            assertEquals("PROGRAM", node.type());
            assertEquals(program, node.label());
        }
        long copybookId = ids.get("copybook/SYKCPY1.cpy");
        assertEquals("COPYBOOK", dao.findNode(copybookId).orElseThrow().type());
        long jclId = ids.get("jcl/SYKD010.jcl");
        var jclNode = dao.findNode(jclId).orElseThrow();
        assertEquals("JCL", jclNode.type());
        assertEquals("SYKD010", jclNode.label());
        assertTrue(count("SELECT COUNT(*) FROM PARAGRAPH") > 0, "段落が保存されること");
    }

    @Test
    void encodingInfoIsPersistedForAllSources() {
        for (SourceRecord source : dao.findAllSources()) {
            var info = dao.findEncodingInfo(source.id()).orElseThrow();
            assertEquals("UTF-8", info.detectedCharset(), source.path());
            assertFalse(info.manualOverride());
            assertEquals("UTF-8", source.codepage());
        }
    }

    @Test
    void copyEdgesMatchExpectedCopybookUsage() {
        Set<String> edges = edgePairs("COPY");
        Set<String> expected = new TreeSet<>(Set.of(
                "copybook/SYKCPY1.cpy->cobol/SYK001.cbl",
                "copybook/SYKCPY1.cpy->cobol/SYK002.cbl",
                "copybook/SYKCPY1.cpy->cobol/SYK003.cbl",
                "copybook/SYKCPY2.cpy->cobol/SYK002.cbl",
                "copybook/SYKCPY3.cpy->cobol/SYK006.cbl",
                "copybook/SYKCPY3.cpy->cobol/SYK007.cbl"));
        assertEquals(expected, edges, "期待結果.md 6章のコピー句使用状況と一致すること");
    }

    @Test
    void executionEdgesMatchExpectedJclProgramCalls() {
        Set<String> edges = edgePairs("EXECUTION");
        Set<String> expected = new TreeSet<>(Set.of(
                "jcl/SYKD010.jcl->cobol/SYK001.cbl",
                "jcl/SYKD010.jcl->cobol/SYK002.cbl",
                "jcl/SYKD020.jcl->cobol/SYK006.cbl",
                "jcl/SYKD020.jcl->cobol/SYK007.cbl",
                "jcl/SYKD030.jcl->cobol/SYK001.cbl",
                "jcl/SYKD030.jcl->cobol/SYK002.cbl"));
        assertEquals(expected, edges, "期待結果.md 4章のEXEC PGM対応と一致すること");
    }

    @Test
    void sqlStatementsArePersistedWithMangledAndOriginalText() {
        Map<String, Long> ids = sourceIdByPath();
        for (String program : List.of("SYK006", "SYK007")) {
            var statements = dao.findSqlStmtsBySource(ids.get("cobol/" + program + ".cbl"));
            assertFalse(statements.isEmpty(), program + " のSQL文が保存されること");
            assertTrue(statements.stream().anyMatch(s -> s.mangledText().contains(":HV1")
                            && s.originalText().contains(":HOST-")),
                    program + " のハイフン入りホスト変数がマングリングされ原文も保持されること");
        }
        var syk006 = dao.findSqlStmtsBySource(ids.get("cobol/SYK006.cbl"));
        Set<String> kinds = syk006.stream().map(s -> s.stmtType()).collect(Collectors.toSet());
        assertTrue(kinds.containsAll(Set.of("SELECT", "UPDATE", "INSERT", "DECLARE_CURSOR",
                "OPEN", "FETCH", "CLOSE")), "SQL文種別が保存されること: " + kinds);
    }

    @Test
    void bmsMapsetMapAndFieldsMatchExpectedStructure() throws SQLException {
        Map<String, Long> ids = sourceIdByPath();
        long bmsSourceId = ids.get("bms/SYKMAP1.bms");

        List<Object[]> mapsets = query(
                "SELECT id, name FROM BMS_MAPSET WHERE source_id = ?", bmsSourceId);
        assertEquals(1, mapsets.size());
        assertEquals("SYKMAP1", mapsets.get(0)[1]);

        List<Object[]> maps = query(
                "SELECT id, name, size_rows, size_cols FROM BMS_MAP WHERE mapset_id = ?",
                mapsets.get(0)[0]);
        assertEquals(1, maps.size());
        assertEquals("SYKM01", maps.get(0)[1]);
        assertEquals(24, ((Number) maps.get(0)[2]).intValue());
        assertEquals(80, ((Number) maps.get(0)[3]).intValue());

        List<Object[]> fields = query(
                "SELECT name, pos_row, pos_col, length, attrb FROM BMS_FIELD WHERE map_id = ? "
                        + "ORDER BY id", maps.get(0)[0]);
        assertEquals(2, fields.size());
        assertEquals("ORDNO", fields.get(0)[0]);
        assertEquals(3, ((Number) fields.get(0)[1]).intValue());
        assertEquals(10, ((Number) fields.get(0)[2]).intValue());
        assertEquals(8, ((Number) fields.get(0)[3]).intValue());
        assertEquals("UNPROT,NUM", fields.get(0)[4]);
        assertEquals("MSG", fields.get(1)[0]);
        assertEquals(22, ((Number) fields.get(1)[1]).intValue());
        assertEquals(5, ((Number) fields.get(1)[2]).intValue());
        assertEquals(40, ((Number) fields.get(1)[3]).intValue());
        assertEquals("PROT,BRT", fields.get(1)[4]);
    }

    @Test
    void rescanIntoAnotherDatabaseAssignsIdenticalSourceIds() {
        Path secondDatabaseFile = tempDir.resolve("m1-second.db");
        ScanRunner.Summary second = ScanRunner.run(new ScanRunner.Options(SAMPLES,
                secondDatabaseFile, List.of(SAMPLES.resolve("copybook")), Map.of()));
        assertEquals(0, second.exitCode());
        try (PersistenceDatabase secondDatabase = PersistenceDatabase.open(secondDatabaseFile)) {
            Map<String, Long> secondIds = new PersistenceDao(secondDatabase.connection())
                    .findAllSources().stream()
                    .collect(Collectors.toMap(SourceRecord::path, SourceRecord::id));
            Map<String, Long> firstIds = sourceIdByPath();
            assertEquals(firstIds, secondIds,
                    "同一入力を別DBへ2回scanしてもSOURCE.path→idの対応が完全一致すること");
            List<String> sortedPaths = firstIds.keySet().stream().sorted().toList();
            for (Map.Entry<String, Long> entry : firstIds.entrySet()) {
                assertEquals(sortedPaths.indexOf(entry.getKey()) + 1L, entry.getValue().longValue(),
                        "IDはパスの辞書順の順位で決まり、ファイル発見順に依存しないこと: "
                                + entry.getKey());
            }
        }
    }

    @Test
    void rescanWithoutChangesSkipsEveryFile() {
        ScanRunner.Summary second = ScanRunner.run(new ScanRunner.Options(SAMPLES,
                tempDir.resolve("m1.db"), List.of(SAMPLES.resolve("copybook")), Map.of()));
        assertEquals(List.of(), second.analyzed(), "変更が無ければ再解析しないこと");
        assertEquals(16, second.skipped().size());
        assertEquals(0, second.exitCode());
    }

    // ---- 検証用の問い合わせ ----

    private Set<String> edgePairs(String kind) {
        Map<Long, String> pathById = dao.findAllSources().stream()
                .collect(Collectors.toMap(SourceRecord::id, SourceRecord::path));
        Set<String> pairs = new TreeSet<>();
        for (SourceRecord source : dao.findAllSources()) {
            for (CallEdgeRecord edge : dao.findEdgesFrom(source.id())) {
                if (kind.equals(edge.kind())) {
                    pairs.add(pathById.get(edge.fromNode()) + "->" + pathById.get(edge.toNode()));
                }
            }
        }
        return pairs;
    }

    private static long count(String sql) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<Object[]> query(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Object[]> rows = new ArrayList<>();
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Object[] row = new Object[columns];
                    for (int i = 0; i < columns; i++) {
                        row[i] = rs.getObject(i + 1);
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }
}
