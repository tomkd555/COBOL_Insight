package jp.cobolinsight.app.persistence;

import jp.cobolinsight.app.persistence.model.CallEdgeRecord;
import jp.cobolinsight.app.persistence.model.NodeRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the node/edge maintenance operations used by scan's incremental updates. 1,000,000,000,000
 * is the lower bound of the IDs scan assigns to the call-relationship graph's build result; IDs below
 * this are occupied by source-level analysis results.
 */
class PersistenceDaoMaintenanceTest {

    @TempDir
    Path tempDir;

    private PersistenceDatabase database;
    private PersistenceDao dao;

    @BeforeEach
    void openDatabase() {
        database = PersistenceDatabase.open(tempDir.resolve("test.db"));
        dao = new PersistenceDao(database.connection());
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    @Test
    void deleteNodeRemovesTheNodeAndItsEdges() {
        dao.insertNode(new NodeRecord(1, "PROGRAM", "SYK001"));
        dao.insertNode(new NodeRecord(2, "JCL", "SYKD010"));
        dao.insertCallEdge(new CallEdgeRecord(10, 2, 1, "EXECUTION", null, null));

        dao.deleteNode(1);

        assertTrue(dao.findNode(1).isEmpty());
        assertTrue(dao.findCallEdge(10).isEmpty(), "エッジはノード削除に連動して消えること");
        assertTrue(dao.findNode(2).isPresent());
    }

    @Test
    void updateNodeReplacesTypeAndLabelKeepingEdges() {
        dao.insertNode(new NodeRecord(1, "PROGRAM", "OLD-NAME"));
        dao.insertNode(new NodeRecord(2, "JCL", "SYKD010"));
        dao.insertCallEdge(new CallEdgeRecord(10, 2, 1, "EXECUTION", null, null));

        dao.updateNode(new NodeRecord(1, "PROGRAM", "NEW-NAME"));

        assertEquals("NEW-NAME", dao.findNode(1).orElseThrow().label());
        assertTrue(dao.findCallEdge(10).isPresent(), "更新ではエッジを失わないこと");
    }

    @Test
    void deleteCallEdgesFromRemovesOnlyTheGivenKind() {
        dao.insertNode(new NodeRecord(1, "JCL", "SYKD010"));
        dao.insertNode(new NodeRecord(2, "PROGRAM", "SYK001"));
        dao.insertCallEdge(new CallEdgeRecord(10, 1, 2, "EXECUTION", null, null));
        dao.insertCallEdge(new CallEdgeRecord(11, 1, 2, "COPY", null, null));

        dao.deleteCallEdgesFrom(1, "EXECUTION");

        assertTrue(dao.findCallEdge(10).isEmpty());
        assertTrue(dao.findCallEdge(11).isPresent());
    }

    @Test
    void deleteCallEdgesToRemovesOnlyTheGivenKind() {
        dao.insertNode(new NodeRecord(1, "COPYBOOK", "SYKCPY1.cpy"));
        dao.insertNode(new NodeRecord(2, "PROGRAM", "SYK001"));
        dao.insertCallEdge(new CallEdgeRecord(10, 1, 2, "COPY", null, null));
        dao.insertCallEdge(new CallEdgeRecord(11, 1, 2, "EXECUTION", null, null));

        dao.deleteCallEdgesTo(2, "COPY");

        assertTrue(dao.findCallEdge(10).isEmpty());
        assertTrue(dao.findCallEdge(11).isPresent());
    }

    @Test
    void deleteNodesIdAtLeastRemovesOnlyGraphLayerNodes() {
        dao.insertNode(new NodeRecord(1, "PROGRAM", "SYK001"));
        dao.insertNode(new NodeRecord(999_999_999_999L, "PROGRAM", "SYK999"));
        dao.insertNode(new NodeRecord(1_000_000_000_000L, "STEP", "STEP010"));
        dao.insertNode(new NodeRecord(1_000_000_000_001L, "DATASET", "SYKT.INPUT.DATA"));

        dao.deleteNodesIdAtLeast(1_000_000_000_000L);

        assertTrue(dao.findNode(1).isPresent(), "ソース由来ノードは残ること");
        assertTrue(dao.findNode(999_999_999_999L).isPresent(), "閾値直下のノードは残ること");
        assertTrue(dao.findNode(1_000_000_000_000L).isEmpty());
        assertTrue(dao.findNode(1_000_000_000_001L).isEmpty());
    }

    @Test
    void deleteCallEdgesIdAtLeastRemovesOnlyGraphLayerEdges() {
        dao.insertNode(new NodeRecord(1, "JCL", "SYKD010"));
        dao.insertNode(new NodeRecord(2, "PROGRAM", "SYK001"));
        dao.insertCallEdge(new CallEdgeRecord(10, 1, 2, "EXECUTION", null, null));
        dao.insertCallEdge(new CallEdgeRecord(999_999_999_999L, 1, 2, "COPY", null, null));
        dao.insertCallEdge(new CallEdgeRecord(1_000_000_000_000L, 1, 2, "CALL", "CONSTANT", null));

        dao.deleteCallEdgesIdAtLeast(1_000_000_000_000L);

        assertTrue(dao.findCallEdge(10).isPresent(), "scanの増分用エッジは残ること");
        assertTrue(dao.findCallEdge(999_999_999_999L).isPresent(), "閾値直下のエッジは残ること");
        assertTrue(dao.findCallEdge(1_000_000_000_000L).isEmpty());
    }

    @Test
    void deleteFindingsIdAtLeastRemovesOnlyGraphLayerFindings() {
        dao.insertSource(new jp.cobolinsight.app.persistence.model.SourceRecord(
                1, "/assets", "cobol/SYK002.cbl", "UTF-8", "h1", 10));
        dao.insertFinding(new jp.cobolinsight.app.persistence.model.FindingRecord(
                1_000_001, "parse-failure", "ERROR", 1, 1, 1, "m"));
        dao.insertFinding(new jp.cobolinsight.app.persistence.model.FindingRecord(
                999_999_999_999L, "parse-failure", "ERROR", 1, 2, 1, "m"));
        dao.insertFinding(new jp.cobolinsight.app.persistence.model.FindingRecord(
                1_000_000_000_000L, "callgraph-dynamic-call", "NOTE", 1, 113, 1, "m"));

        dao.deleteFindingsIdAtLeast(1_000_000_000_000L);

        assertTrue(dao.findFinding(1_000_001).isPresent(), "scan由来のfindingは残ること");
        assertTrue(dao.findFinding(999_999_999_999L).isPresent(), "閾値直下のfindingは残ること");
        assertTrue(dao.findFinding(1_000_000_000_000L).isEmpty());
    }

    @Test
    void findAllSourcesReturnsRowsOrderedById() {
        dao.insertSource(new jp.cobolinsight.app.persistence.model.SourceRecord(
                2, "/assets", "cobol/SYK001.cbl", "UTF-8", "h2", 10));
        dao.insertSource(new jp.cobolinsight.app.persistence.model.SourceRecord(
                1, "/assets", "bms/SYKMAP1.bms", "UTF-8", "h1", 20));

        var sources = dao.findAllSources();

        assertEquals(2, sources.size());
        assertEquals(1, sources.get(0).id());
        assertEquals(2, sources.get(1).id());
    }
}
