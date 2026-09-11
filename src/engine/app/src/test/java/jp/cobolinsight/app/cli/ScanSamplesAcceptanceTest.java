package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.ScanOutcome;
import jp.cobolinsight.app.pipeline.Persist;
import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.CallEdgeRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance regression test for scan. Verifies that scanning the entire samples/ succeeds and
 * that the expected rows land in SQLite, by cross-checking against the values in
 * expected-results.md chapters 4, 6, and 9.
 */
class ScanSamplesAcceptanceTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    @TempDir
    static Path tempDir;

    private static ScanOutcome.Summary summary;
    private static PersistenceDatabase database;
    private static PersistenceDao dao;

    @BeforeAll
    static void scanSamples() {
        Path databaseFile = tempDir.resolve("m1.db");
        summary = Pipelines.scan(SAMPLES, databaseFile,
                List.of(SAMPLES.resolve("copybook")), Map.of()).summary();
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
    void everySourceIsScannedWithoutError() {
        assertEquals(0, summary.exitCode(), "終了コードは成功(0)であること");
        assertEquals(0, summary.findingCount(), "パース失敗のfindingが無いこと");
        assertEquals(23, summary.analyzed().size(),
                "JCL4本・COBOL15本(encoding/の4本を含む)・コピー句3本・BMSマップ1本の計23本を"
                        + "解析すること");
        assertEquals(23, dao.findAllSources().size());
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
        assertEquals(23, sources.stream().mapToLong(SourceRecord::id).max().orElseThrow());
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
            // SJIS sample is windows-31j; both EBCDIC samples are estimated as x-IBM930
            // (IBM930 and IBM939 cannot be told apart by byte distribution); the rest are UTF-8.
            String expected = switch (source.path()) {
                case "encoding/SYKENC1_SJIS.cbl" -> "windows-31j";
                case "encoding/SYKENC1_CP930.cbl", "encoding/SYKENC1_CP939.cbl" -> "x-IBM930";
                default -> "UTF-8";
            };
            assertEquals(expected, source.codepage(), source.path());
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
        assertEquals(expected, edges, "expected-results.md 6章のコピー句使用状況と一致すること");
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
                "jcl/SYKD030.jcl->cobol/SYK002.cbl",
                "jcl/SYKD040.jcl->cobol/SYK005.cbl",
                "jcl/SYKD040.jcl->cobol/SYK010.cbl",
                "jcl/SYKD040.jcl->cobol/SYK011.cbl"));
        assertEquals(expected, edges, "expected-results.md 4章のEXEC PGM対応と一致すること");
    }

    @Test
    void rescanIntoAnotherDatabaseAssignsIdenticalSourceIds() {
        Path secondDatabaseFile = tempDir.resolve("m1-second.db");
        ScanOutcome.Summary second = Pipelines.scan(SAMPLES,
                secondDatabaseFile, List.of(SAMPLES.resolve("copybook")), Map.of()).summary();
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
        ScanOutcome.Summary second = Pipelines.scan(SAMPLES,
                tempDir.resolve("m1.db"), List.of(SAMPLES.resolve("copybook")), Map.of()).summary();
        assertEquals(List.of(), second.analyzed(), "変更が無ければ再解析しないこと");
        assertEquals(23, second.skipped().size());
        assertEquals(0, second.exitCode());
    }

    // ---- queries for verification ----

    private Set<String> edgePairs(String kind) {
        Map<Long, String> pathById = dao.findAllSources().stream()
                .collect(Collectors.toMap(SourceRecord::id, SourceRecord::path));
        Set<String> pairs = new TreeSet<>();
        for (SourceRecord source : dao.findAllSources()) {
            for (CallEdgeRecord edge : dao.findEdgesFrom(source.id())) {
                // Excludes the call-graph layer (id at or above the lower bound). Here we only count scan's incremental edges
                if (edge.id() >= Persist.GRAPH_ID_BASE) {
                    continue;
                }
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
}
