package jp.cobolinsight.persistence;

import jp.cobolinsight.persistence.model.CallEdgeRecord;
import jp.cobolinsight.persistence.model.NodeRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CallGraphReachabilityTest {

    private static final long JCL = 1L;
    private static final long PROGRAM = 2L;
    private static final long SUBROUTINE = 3L;
    private static final long DATASET = 4L;

    private PersistenceDatabase db;
    private PersistenceDao dao;

    @BeforeEach
    void openDatabase(@TempDir Path dir) {
        db = PersistenceDatabase.open(dir.resolve("insight.db"));
        dao = new PersistenceDao(db.connection());
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }

    @Test
    void reachesAllDownstreamLayersWithoutCycle() {
        dao.insertNode(new NodeRecord(JCL, "JOB", "JOB1"));
        dao.insertNode(new NodeRecord(PROGRAM, "PROGRAM", "PROGA"));
        dao.insertNode(new NodeRecord(SUBROUTINE, "PROGRAM", "SUBB"));
        dao.insertNode(new NodeRecord(DATASET, "DATASET", "CUST.FILE"));

        dao.insertCallEdge(new CallEdgeRecord(1L, JCL, PROGRAM, "EXECUTION", "CONSTANT", null));
        dao.insertCallEdge(new CallEdgeRecord(2L, PROGRAM, SUBROUTINE, "CALL", "CONSTANT", null));
        dao.insertCallEdge(new CallEdgeRecord(3L, SUBROUTINE, DATASET, "REFERENCE", "CONSTANT", null));

        assertEquals(Set.of(PROGRAM, SUBROUTINE, DATASET), dao.reachableFrom(JCL));
        assertEquals(Set.of(SUBROUTINE, DATASET), dao.reachableFrom(PROGRAM));
        assertEquals(Set.of(), dao.reachableFrom(DATASET));
    }

    @Test
    void terminatesAndIncludesSelfWhenGraphHasCycle() {
        long a = 10L;
        long b = 11L;
        long c = 12L;
        dao.insertNode(new NodeRecord(a, "PROGRAM", "A"));
        dao.insertNode(new NodeRecord(b, "PROGRAM", "B"));
        dao.insertNode(new NodeRecord(c, "PROGRAM", "C"));

        dao.insertCallEdge(new CallEdgeRecord(1L, a, b, "CALL", "CONSTANT", null));
        dao.insertCallEdge(new CallEdgeRecord(2L, b, c, "CALL", "CONSTANT", null));
        dao.insertCallEdge(new CallEdgeRecord(3L, c, a, "CALL", "CONSTANT", null));

        assertEquals(Set.of(a, b, c), dao.reachableFrom(a));
    }
}
