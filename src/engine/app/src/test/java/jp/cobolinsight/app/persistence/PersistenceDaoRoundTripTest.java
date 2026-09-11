package jp.cobolinsight.app.persistence;

import jp.cobolinsight.app.persistence.model.CallEdgeRecord;
import jp.cobolinsight.app.persistence.model.FindingRecord;
import jp.cobolinsight.app.persistence.model.JclDdRecord;
import jp.cobolinsight.app.persistence.model.JclStepRecord;
import jp.cobolinsight.app.persistence.model.LineMapRecord;
import jp.cobolinsight.app.persistence.model.NodeRecord;
import jp.cobolinsight.app.persistence.model.ParagraphEdgeRecord;
import jp.cobolinsight.app.persistence.model.ParagraphRecord;
import jp.cobolinsight.app.persistence.model.ProgramRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import jp.cobolinsight.app.persistence.model.SqlColumnUseRecord;
import jp.cobolinsight.app.persistence.model.SqlStatementRecord;
import jp.cobolinsight.app.persistence.model.SqlTableUseRecord;
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
        // A GO TO with no target paragraph keeps only the name and has no target ID
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
    void callEdgeCarriesItsAccessAndTheRestOfItsAttributes() {
        dao.insertNode(new NodeRecord(1L, "STEP", "STEP010"));
        dao.insertNode(new NodeRecord(2L, "DATASET", "SYKT.ORDER.DAILY"));
        // access has a column of its own, so attrs_json carries what is left beside it.
        CallEdgeRecord edge = new CallEdgeRecord(1L, 1L, 2L, "REFERENCE", "CONSTANT", null, 1, 16,
                "READ", "{\"launcher\":\"IKJEFT01\"}");
        dao.insertCallEdge(edge);
        assertEquals(edge, dao.findCallEdge(1L).orElseThrow());
    }

    @Test
    void jclStepAndDdRoundTrip() {
        dao.insertSource(new SourceRecord(1L, "/assets", "jcl/A.jcl", "IBM930", "hash-1", 10L));
        JclStepRecord step = new JclStepRecord(1_000_001L, 1L, "JOBA", 1, "STEP010", "PGM",
                "PGMA", null, 14, "jcl/A.jcl", "{\"parameters\":{}}");
        JclStepRecord expanded = new JclStepRecord(1_000_002L, 1L, "JOBA", 2, "STEP020.STEP1",
                "PGM", "PGMB", "STEP1", 4, "jcl/A.proc", "{\"parameters\":{}}");
        dao.insertJclStep(step);
        dao.insertJclStep(expanded);
        assertEquals(List.of(step, expanded), dao.findJclStepsBySource(1L));

        JclDdRecord withDataset = new JclDdRecord(1_000_001L, 1_000_001L, 1, "ORDIN",
                "SYKT.ORDER.DAILY", "READ", 15, "jcl/A.jcl", "{\"inStreamLines\":0}");
        // A SYSOUT DD names no data set at all, so its dsn stays empty.
        JclDdRecord withoutDataset = new JclDdRecord(1_000_002L, 1_000_001L, 2, "SYSOUT", null,
                "WRITE", 16, "jcl/A.jcl", "{\"inStreamLines\":0}");
        dao.insertJclDd(withDataset);
        dao.insertJclDd(withoutDataset);
        assertEquals(List.of(withDataset, withoutDataset), dao.findJclDdsByStep(1_000_001L));
    }

    @Test
    void deletingSourceRemovesItsStepsAndTheirDdStatements() {
        dao.insertSource(new SourceRecord(1L, "/assets", "jcl/A.jcl", "IBM930", "hash-1", 10L));
        dao.insertJclStep(new JclStepRecord(1_000_001L, 1L, "JOBA", 1, "STEP010", "PGM", "PGMA",
                null, 14, "jcl/A.jcl", "{}"));
        dao.insertJclDd(new JclDdRecord(1_000_001L, 1_000_001L, 1, "ORDIN", "SYKT.ORDER.DAILY",
                "READ", 15, "jcl/A.jcl", "{}"));

        dao.deleteSourceCascade(1L);
        assertEquals(List.of(), dao.findJclStepsBySource(1L));
        assertEquals(List.of(), dao.findJclDdsByStep(1_000_001L),
                "DD の削除は JCL_STEP 経由の二段カスケードで起きる");
    }

    @Test
    void sqlStatementWithItsTableAndColumnUsesRoundTrip() {
        dao.insertSource(new SourceRecord(1L, "/assets", "cobol/A.cbl", "IBM930", "hash-1", 10L));
        dao.insertProgram(new ProgramRecord(1L, 1L, "PROGA"));
        SqlStatementRecord statement = new SqlStatementRecord(1_000_001L, 1L, 1L, 1, "SELECT",
                "CSR-ORDER", 200, 204, "FULL", "SELECT A FROM SYKDB.ZAIKOM", "cobol/A.cbl",
                "{\"hasWhere\":true}");
        dao.insertSqlStatement(statement);
        assertEquals(List.of(statement), dao.findSqlStatementsBySource(1L));

        SqlTableUseRecord table =
                new SqlTableUseRecord(1_000_001L, 1_000_001L, "SYKDB.ZAIKOM", "R");
        dao.insertSqlTableUse(table);
        assertEquals(List.of(table), dao.findSqlTableUsesByStatement(1_000_001L));

        SqlColumnUseRecord qualified =
                new SqlColumnUseRecord(1_000_001L, 1_000_001L, "SYKDB.ZAIKOM", "ZAIKO-SU");
        // An unqualified column keeps no table.
        SqlColumnUseRecord bare = new SqlColumnUseRecord(1_000_002L, 1_000_001L, null, "SOKO-CD");
        dao.insertSqlColumnUse(qualified);
        dao.insertSqlColumnUse(bare);
        assertEquals(List.of(qualified, bare), dao.findSqlColumnUsesByStatement(1_000_001L));

        dao.deleteSourceCascade(1L);
        assertEquals(List.of(), dao.findSqlStatementsBySource(1L));
        assertEquals(List.of(), dao.findSqlTableUsesByStatement(1_000_001L));
        assertEquals(List.of(), dao.findSqlColumnUsesByStatement(1_000_001L));
    }

    /** A source with no PROGRAM row of its own still records the statements it holds. */
    @Test
    void anSqlStatementOfAnUnparsedSourceKeepsNoProgram() {
        dao.insertSource(new SourceRecord(1L, "/assets", "cobol/A.cbl", "IBM930", "hash-1", 10L));
        dao.insertSqlStatement(new SqlStatementRecord(1_000_001L, 1L, null, 1, "OTHER", null,
                12, 12, "DEGRADED", "EXEC SQL WHATEVER END-EXEC", "cobol/A.cbl", "{}"));
        assertNull(dao.findSqlStatementsBySource(1L).get(0).programId());
    }

    /**
     * A statement the program takes in through a COPY carries the copybook's lines, so its row
     * names the copybook while the source it belongs to stays the program.
     */
    @Test
    void anSqlStatementFromACopybookNamesTheCopybook() {
        dao.insertSource(new SourceRecord(1L, "/assets", "cobol/A.cbl", "IBM930", "hash-1", 10L));
        dao.insertProgram(new ProgramRecord(1L, 1L, "PROGA"));
        dao.insertSqlStatement(new SqlStatementRecord(1_000_001L, 1L, 1L, 1, "DECLARE_TABLE",
                null, 7, 19, "FULL", "DECLARE SYKDB.ZAIKOM TABLE", "copybook/SYKDCL1.cpy",
                "{\"declaredTable\":\"SYKDB.ZAIKOM\"}"));
        SqlStatementRecord stored = dao.findSqlStatementsBySource(1L).get(0);
        assertEquals("copybook/SYKDCL1.cpy", stored.file());
        assertEquals(7, stored.line());
        assertEquals(1L, stored.sourceId());
    }

    @Test
    void findingRoundTrip() {
        dao.insertSource(new SourceRecord(1L, "/assets", "A.cbl", "IBM930", "hash-1", 10L));
        FindingRecord finding = new FindingRecord(1L, "R001", "warning", 1L, 5, 8,
                "未初期化の項目を参照している");
        dao.insertFinding(finding);
        assertEquals(finding, dao.findFinding(1L).orElseThrow());
        assertEquals(1, dao.findFindingsBySource(1L).size());
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
