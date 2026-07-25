package jp.cobolinsight.persistence;

import jp.cobolinsight.persistence.model.CallEdgeRecord;
import jp.cobolinsight.persistence.model.NodeRecord;
import jp.cobolinsight.persistence.model.SourceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 依存範囲は「当該コピー句を取り込むプログラム」と「当該プログラムを呼ぶJCL」の2種に限る。
 * 合成グラフ: COPYBOOK(1) が PROGA(2)・PROGB(3) に取り込まれ、
 * JOB1(5) が PROGA(2) を実行する。PROGC(4)・JOB2(6) はどの依存にも属さない。
 */
class IncrementalAnalysisPlannerTest {

    private static final long COPYBOOK = 1L;
    private static final long PROGA = 2L;
    private static final long PROGB = 3L;
    private static final long PROGC = 4L;
    private static final long JOB1 = 5L;
    private static final long JOB2 = 6L;

    private PersistenceDatabase db;
    private PersistenceDao dao;
    private IncrementalAnalysisPlanner planner;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        db = PersistenceDatabase.open(dir.resolve("insight.db"));
        dao = new PersistenceDao(db.connection());
        planner = new IncrementalAnalysisPlanner(dao);

        dao.insertSource(new SourceRecord(COPYBOOK, "CUST.cpy", null, "hash-copybook", 10L));
        dao.insertSource(new SourceRecord(PROGA, "PROGA.cbl", "IBM930", "hash-proga", 20L));
        dao.insertSource(new SourceRecord(PROGB, "PROGB.cbl", "IBM930", "hash-progb", 20L));
        dao.insertSource(new SourceRecord(PROGC, "PROGC.cbl", "IBM930", "hash-progc", 20L));
        dao.insertSource(new SourceRecord(JOB1, "JOB1.jcl", null, "hash-job1", 5L));
        dao.insertSource(new SourceRecord(JOB2, "JOB2.jcl", null, "hash-job2", 5L));

        for (long id : new long[] {COPYBOOK, PROGA, PROGB, PROGC, JOB1, JOB2}) {
            dao.insertNode(new NodeRecord(id, "SOURCE", "node-" + id));
        }
        dao.insertCallEdge(new CallEdgeRecord(1L, COPYBOOK, PROGA, IncrementalAnalysisPlanner.COPY_EDGE_KIND, null, null));
        dao.insertCallEdge(new CallEdgeRecord(2L, COPYBOOK, PROGB, IncrementalAnalysisPlanner.COPY_EDGE_KIND, null, null));
        dao.insertCallEdge(new CallEdgeRecord(3L, JOB1, PROGA, IncrementalAnalysisPlanner.EXECUTION_EDGE_KIND, "CONSTANT", null));
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
    void unrelatedSourceChangeTargetsOnlyItself() {
        assertEquals(Set.of(PROGC), planner.determineReanalysisTargets(PROGC, "hash-progc-v2"));
        assertEquals(Set.of(JOB2), planner.determineReanalysisTargets(JOB2, "hash-job2-v2"));
    }
}
