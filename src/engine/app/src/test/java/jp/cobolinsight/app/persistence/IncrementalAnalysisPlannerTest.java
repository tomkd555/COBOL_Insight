package jp.cobolinsight.app.persistence;

import jp.cobolinsight.app.persistence.model.CallEdgeRecord;
import jp.cobolinsight.app.persistence.model.NodeRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The dependency scope is limited to three kinds: "programs that include the given copybook",
 * "JCL that expands the given PROC or INCLUDE member" and "JCL that calls the given program".
 * Graph used for verification: COPYBOOK(1) is included by PROGA(2) and PROGB(3), JOB1(5) runs
 * PROGA(2) and expands MEMBER(7). PROGC(4) and JOB2(6) belong to no dependency.
 */
class IncrementalAnalysisPlannerTest {

    private static final long COPYBOOK = 1L;
    private static final long PROGA = 2L;
    private static final long PROGB = 3L;
    private static final long PROGC = 4L;
    private static final long JOB1 = 5L;
    private static final long JOB2 = 6L;
    private static final long MEMBER = 7L;

    private PersistenceDatabase db;
    private PersistenceDao dao;
    private IncrementalAnalysisPlanner planner;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        db = PersistenceDatabase.open(dir.resolve("insight.db"));
        dao = new PersistenceDao(db.connection());
        planner = new IncrementalAnalysisPlanner(dao);

        dao.insertSource(new SourceRecord(COPYBOOK, "/assets", "CUST.cpy", null, "hash-copybook", 10L));
        dao.insertSource(new SourceRecord(PROGA, "/assets", "PROGA.cbl", "IBM930", "hash-proga", 20L));
        dao.insertSource(new SourceRecord(PROGB, "/assets", "PROGB.cbl", "IBM930", "hash-progb", 20L));
        dao.insertSource(new SourceRecord(PROGC, "/assets", "PROGC.cbl", "IBM930", "hash-progc", 20L));
        dao.insertSource(new SourceRecord(JOB1, "/assets", "JOB1.jcl", null, "hash-job1", 5L));
        dao.insertSource(new SourceRecord(JOB2, "/assets", "JOB2.jcl", null, "hash-job2", 5L));
        dao.insertSource(new SourceRecord(MEMBER, "/assets", "PROC1.proc", null, "hash-member", 5L));

        for (long id : new long[] {COPYBOOK, PROGA, PROGB, PROGC, JOB1, JOB2, MEMBER}) {
            dao.insertNode(new NodeRecord(id, "SOURCE", "node-" + id));
        }
        dao.insertCallEdge(new CallEdgeRecord(1L, COPYBOOK, PROGA, IncrementalAnalysisPlanner.COPY_EDGE_KIND, null, null));
        dao.insertCallEdge(new CallEdgeRecord(2L, COPYBOOK, PROGB, IncrementalAnalysisPlanner.COPY_EDGE_KIND, null, null));
        dao.insertCallEdge(new CallEdgeRecord(3L, JOB1, PROGA, IncrementalAnalysisPlanner.EXECUTION_EDGE_KIND, "CONSTANT", null));
        dao.insertCallEdge(new CallEdgeRecord(4L, MEMBER, JOB1, IncrementalAnalysisPlanner.INCLUDE_EDGE_KIND, null, null));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void unchangedHashRequiresNoReanalysis() {
        assertEquals(Set.of(), planner.determineReanalysisTargets(PROGA, "hash-proga"));
    }

    @Test
    void changedCopybookTargetsItselfAndBothImportingPrograms() {
        assertEquals(Set.of(COPYBOOK, PROGA, PROGB),
                planner.determineReanalysisTargets(COPYBOOK, "hash-copybook-v2"));
    }

    @Test
    void changedProgramTargetsItselfAndInvokingJcl() {
        assertEquals(Set.of(PROGA, JOB1),
                planner.determineReanalysisTargets(PROGA, "hash-proga-v2"));
    }

    @Test
    void changedMemberTargetsItselfAndTheJobThatExpandsIt() {
        assertEquals(Set.of(MEMBER, JOB1),
                planner.determineReanalysisTargets(MEMBER, "hash-member-v2"));
    }

    @Test
    void unrelatedSourceChangeTargetsOnlyItself() {
        assertEquals(Set.of(PROGC), planner.determineReanalysisTargets(PROGC, "hash-progc-v2"));
        assertEquals(Set.of(JOB2), planner.determineReanalysisTargets(JOB2, "hash-job2-v2"));
    }
}
