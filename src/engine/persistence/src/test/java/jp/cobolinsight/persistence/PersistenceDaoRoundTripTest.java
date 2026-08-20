package jp.cobolinsight.persistence;

import jp.cobolinsight.persistence.model.BmsFieldRecord;
import jp.cobolinsight.persistence.model.BmsMapRecord;
import jp.cobolinsight.persistence.model.BmsMapsetRecord;
import jp.cobolinsight.persistence.model.CallEdgeRecord;
import jp.cobolinsight.persistence.model.EncodingInfoRecord;
import jp.cobolinsight.persistence.model.FindingRecord;
import jp.cobolinsight.persistence.model.LineMapRecord;
import jp.cobolinsight.persistence.model.NodeRecord;
import jp.cobolinsight.persistence.model.ParagraphEdgeRecord;
import jp.cobolinsight.persistence.model.ParagraphRecord;
import jp.cobolinsight.persistence.model.ProgramRecord;
import jp.cobolinsight.persistence.model.SourceRecord;
import jp.cobolinsight.persistence.model.SqlStmtRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceDaoRoundTripTest {

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
    void sourceRoundTrip() {
        SourceRecord source = new SourceRecord(1L, "/assets", "COPYBOOKS/CUST.cpy", "IBM930", "hash-abc", 512L);
        dao.insertSource(source);
        assertEquals(source, dao.findSource(1L).orElseThrow());
        assertEquals(source, dao.findSourceByPath("/assets", "COPYBOOKS/CUST.cpy").orElseThrow());
    }

    @Test
    void encodingInfoRoundTrip() {
        dao.insertSource(new SourceRecord(1L, "/assets", "A.cbl", "IBM930", "hash-1", 10L));
        EncodingInfoRecord info = new EncodingInfoRecord(1L, "IBM930", 0.95, false, true);
        dao.insertEncodingInfo(info);
        assertEquals(info, dao.findEncodingInfo(1L).orElseThrow());
    }

    @Test
    void bmsHierarchyRoundTrip() {
        dao.insertSource(new SourceRecord(1L, "/assets", "SCREEN.bms", null, "hash-1", 10L));
        BmsMapsetRecord mapset = new BmsMapsetRecord(1L, 1L, "MAPSET1");
        dao.insertBmsMapset(mapset);
        assertEquals(mapset, dao.findBmsMapset(1L).orElseThrow());

        BmsMapRecord map = new BmsMapRecord(1L, 1L, "MAP1", 24, 80);
        dao.insertBmsMap(map);
        assertEquals(map, dao.findBmsMap(1L).orElseThrow());

        BmsFieldRecord field = new BmsFieldRecord(1L, 1L, "FLD1", 1, 1, 10, "UNPROT");
        dao.insertBmsField(field);
        assertEquals(field, dao.findBmsField(1L).orElseThrow());
    }

    @Test
    void programAndParagraphRoundTrip() {
        dao.insertSource(new SourceRecord(1L, "/assets", "A.cbl", "IBM930", "hash-1", 10L));
        ProgramRecord program = new ProgramRecord(1L, 1L, "PROGA");
        dao.insertProgram(program);
        assertEquals(program, dao.findProgram(1L).orElseThrow());

        ParagraphRecord paragraph = new ParagraphRecord(1L, 1L, "MAIN-PARA", 10, 20);
        dao.insertParagraph(paragraph);
        assertEquals(paragraph, dao.findParagraph(1L).orElseThrow());
    }

    @Test
    void nodeAndCallEdgeRoundTrip() {
        dao.insertNode(new NodeRecord(1L, "PROGRAM", "PROGA"));
        dao.insertNode(new NodeRecord(2L, "PROGRAM", "PROGB"));
        CallEdgeRecord edge = new CallEdgeRecord(1L, 1L, 2L, "CALL", "CONSTANT", null);
        dao.insertCallEdge(edge);

        assertEquals(edge, dao.findCallEdge(1L).orElseThrow());
        assertEquals(0, edge.seq(), "順序を伴わない辺のseqは0");
        assertNull(edge.line());
        assertEquals(1, dao.findEdgesFrom(1L).size());
        assertEquals(1, dao.findEdgesTo(2L).size());
    }

    @Test
    void callEdgeKeepsOrderAndCallSiteLine() {
        dao.insertNode(new NodeRecord(1L, "JOB", "JOB1"));
        dao.insertNode(new NodeRecord(2L, "STEP", "STEP010"));
        dao.insertNode(new NodeRecord(3L, "STEP", "STEP020"));
        CallEdgeRecord second = new CallEdgeRecord(1L, 1L, 3L, "EXECUTION", "CONSTANT", null, 2, 9);
        CallEdgeRecord first = new CallEdgeRecord(2L, 1L, 2L, "EXECUTION", "CONSTANT", null, 1, 4);
        dao.insertCallEdge(second);
        dao.insertCallEdge(first);

        assertEquals(second, dao.findCallEdge(1L).orElseThrow());
        assertEquals(List.of(2, 1), dao.findEdgesFrom(1L).stream().map(CallEdgeRecord::seq).toList(),
                "行の並びは辺のID順のままで、原本の順序はseqが持つ");
        assertEquals(List.of(9, 4), dao.findEdgesFrom(1L).stream().map(CallEdgeRecord::line).toList());
    }

    @Test
    void paragraphEdgeRoundTripIncludingUnresolvedTarget() {
        dao.insertSource(new SourceRecord(1L, "/assets", "A.cbl", "IBM930", "hash-1", 10L));
        dao.insertProgram(new ProgramRecord(1L, 1L, "PROGA"));
        dao.insertParagraph(new ParagraphRecord(1L, 1L, "MAIN-RTN", 10, 20));
        dao.insertParagraph(new ParagraphRecord(2L, 1L, "SUB-RTN", 21, 30));

        ParagraphEdgeRecord perform =
                new ParagraphEdgeRecord(1L, 1L, 1L, 2L, "SUB-RTN", "PERFORM", 12, 1);
        ParagraphEdgeRecord fallthrough =
                new ParagraphEdgeRecord(2L, 1L, 1L, 2L, "SUB-RTN", "FALLTHROUGH", null, 2);
        // 飛び先の段落が無いGO TOは、名前だけを残して飛び先IDを持たない
        ParagraphEdgeRecord unresolved =
                new ParagraphEdgeRecord(3L, 1L, 2L, null, "NO-SUCH-RTN", "GOTO", 25, 1);
        dao.insertParagraphEdge(perform);
        dao.insertParagraphEdge(fallthrough);
        dao.insertParagraphEdge(unresolved);

        assertEquals(List.of(perform, fallthrough, unresolved),
                dao.findParagraphEdgesByProgram(1L));
    }

    @Test
    void paragraphEdgeRejectsUnknownKind() {
        dao.insertSource(new SourceRecord(1L, "/assets", "A.cbl", "IBM930", "hash-1", 10L));
        dao.insertProgram(new ProgramRecord(1L, 1L, "PROGA"));
        dao.insertParagraph(new ParagraphRecord(1L, 1L, "MAIN-RTN", 10, 20));
        assertThrows(PersistenceException.class, () -> dao.insertParagraphEdge(
                new ParagraphEdgeRecord(1L, 1L, 1L, 1L, "MAIN-RTN", "CALL", 12, 1)));
    }

    @Test
    void deletingSourceRemovesItsParagraphEdges() {
        dao.insertSource(new SourceRecord(1L, "/assets", "A.cbl", "IBM930", "hash-1", 10L));
        dao.insertProgram(new ProgramRecord(1L, 1L, "PROGA"));
        dao.insertParagraph(new ParagraphRecord(1L, 1L, "MAIN-RTN", 10, 20));
        dao.insertParagraphEdge(
                new ParagraphEdgeRecord(1L, 1L, 1L, 1L, "MAIN-RTN", "GOTO", 12, 1));

        dao.deleteSourceCascade(1L);
        assertEquals(List.of(), dao.findParagraphEdgesByProgram(1L));
    }

    @Test
    void findingRoundTrip() {
        dao.insertSource(new SourceRecord(1L, "/assets", "A.cbl", "IBM930", "hash-1", 10L));
        FindingRecord finding = new FindingRecord(1L, "R001", "warning", 1L, 5, 8, 120L,
                "未初期化の項目を参照している", "{\"ruleId\":\"R001\"}");
        dao.insertFinding(finding);
        assertEquals(finding, dao.findFinding(1L).orElseThrow());
        assertEquals(1, dao.findFindingsBySource(1L).size());
    }

    @Test
    void sqlStmtRoundTrip() {
        dao.insertSource(new SourceRecord(1L, "/assets", "A.cbl", "IBM930", "hash-1", 10L));
        SqlStmtRecord stmt = new SqlStmtRecord(1L, 1L, "SELECT", "SELECT * FROM T WHERE K = :H1",
                "SELECT * FROM T WHERE K = :CUST-ID");
        dao.insertSqlStmt(stmt);
        assertEquals(stmt, dao.findSqlStmt(1L).orElseThrow());
        assertEquals(1, dao.findSqlStmtsBySource(1L).size());
    }

    @Test
    void lineMapRoundTrip() {
        dao.insertSource(new SourceRecord(1L, "/assets", "A.cbl", "IBM930", "hash-1", 10L));
        LineMapRecord lineMap = new LineMapRecord(1L, 1L, 10, 12, "A.py", 20, 21, "1:N",
                "GO TO は構造化のため N:1 対応で表現する", "anchor-1");
        dao.insertLineMap(lineMap);
        assertEquals(lineMap, dao.findLineMap(1L).orElseThrow());
        assertEquals(1, dao.findLineMapsBySource(1L).size());
    }

    @Test
    void lineMapRoundTripWithEmptyNote() {
        dao.insertSource(new SourceRecord(1L, "/assets", "A.cbl", "IBM930", "hash-1", 10L));
        LineMapRecord lineMap = new LineMapRecord(1L, 1L, 1, 1, "A.py", 1, 1, "1:1", "", "anchor-2");
        dao.insertLineMap(lineMap);
        assertEquals("", dao.findLineMap(1L).orElseThrow().note());
    }

    @Test
    void deleteLineMapsBySourceRemovesOnlyThatSourcesRows() {
        dao.insertSource(new SourceRecord(1L, "/assets", "A.cbl", "IBM930", "hash-1", 10L));
        dao.insertSource(new SourceRecord(2L, "/assets", "B.cbl", "IBM930", "hash-2", 10L));
        dao.insertLineMap(new LineMapRecord(1L, 1L, 1, 1, "A.py", 1, 1, "1:1", "", "a#1"));
        dao.insertLineMap(new LineMapRecord(2L, 1L, 2, 2, "A.py", 2, 2, "1:1", "", "a#2"));
        dao.insertLineMap(new LineMapRecord(3L, 2L, 1, 1, "B.py", 1, 1, "1:1", "", "b#1"));

        dao.deleteLineMapsBySource(1L);

        assertTrue(dao.findLineMapsBySource(1L).isEmpty());
        assertEquals(1, dao.findLineMapsBySource(2L).size());
    }

    @Test
    void unknownIdReturnsEmpty() {
        assertTrue(dao.findSource(999L).isEmpty());
        assertTrue(dao.findNode(999L).isEmpty());
    }
}
