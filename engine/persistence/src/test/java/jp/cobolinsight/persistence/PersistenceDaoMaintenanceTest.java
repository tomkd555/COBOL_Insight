package jp.cobolinsight.persistence;

import jp.cobolinsight.persistence.model.CallEdgeRecord;
import jp.cobolinsight.persistence.model.NodeRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** scan の増分更新が用いるノード・エッジの保守操作の検証。 */
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
    void findAllSourcesReturnsRowsOrderedById() {
        dao.insertSource(new jp.cobolinsight.persistence.model.SourceRecord(
                2, "cobol/SYK001.cbl", "UTF-8", "h2", 10));
        dao.insertSource(new jp.cobolinsight.persistence.model.SourceRecord(
                1, "bms/SYKMAP1.bms", "UTF-8", "h1", 20));

        var sources = dao.findAllSources();

        assertEquals(2, sources.size());
        assertEquals(1, sources.get(0).id());
        assertEquals(2, sources.get(1).id());
    }
}
