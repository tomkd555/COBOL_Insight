package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.FindingRecord;
import jp.cobolinsight.app.persistence.model.NodeRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import jp.cobolinsight.app.persistence.model.SqlStatementRecord;
import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.ScanOutcome;
import jp.cobolinsight.app.pipeline.SourceSet;
import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.callgraph.CallGraphNode;
import jp.cobolinsight.core.callgraph.EdgeKind;
import jp.cobolinsight.core.callgraph.NodeKind;
import jp.cobolinsight.core.sql.SqlStatementModel;
import jp.cobolinsight.rules.RuleSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification of scanning SQL scripts, over {@code corpus-constructs/ddl}: a DDL member, a member
 * holding a native SQL PL procedure, a function and a trigger, and a SPUFI member.
 *
 * <p>The folder is the ground truth of what a script contributes — a table with its column count, a
 * routine as a program of its own, and the DML a SPUFI member runs, which nothing calls.
 */
class ScanSqlScriptTest {

    private static final Path DDL =
            Path.of("..", "..", "..", "corpus-constructs", "ddl").toAbsolutePath().normalize();

    private static ScanOutcome outcome;
    private static Path databaseFile;

    @BeforeAll
    static void scan() throws Exception {
        assertTrue(Files.isDirectory(DDL), DDL + " が無いこと");
        databaseFile = Files.createTempDirectory("cobol-insight-sql-script").resolve("scan.db");
        outcome = Pipelines.scan(DDL, databaseFile, List.of(), Map.of());
    }

    private static CallGraphNode nodeOf(String id) {
        return outcome.callGraph().nodes().stream().filter(node -> node.id().equals(id))
                .findFirst().orElseThrow(() -> new AssertionError(id + " のノードが無い: "
                        + outcome.callGraph().nodes().stream().map(CallGraphNode::id).toList()));
    }

    private static List<SqlStatementRecord> statementsOf(String relPath) {
        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            SourceRecord source = dao.findAllSources().stream()
                    .filter(row -> row.path().equals(relPath)).findFirst()
                    .orElseThrow(() -> new AssertionError(relPath + " の SOURCE 行が無い"));
            return dao.findSqlStatementsBySource(source.id());
        }
    }

    /** Every script is taken in, and none of them is left as a file of undecided kind. */
    @Test
    void everyScriptIsDiscoveredAsAnAsset() {
        assertEquals(List.of(), outcome.summary().undecided());
        assertEquals(List.of("CCPTAB.sql", "CSL501.sql", "CSL502.spufi"),
                outcome.summary().analyzed());
    }

    /** The statement count of each script, which is what the splitter is measured by. */
    @Test
    void eachScriptIsSplitIntoItsStatements() {
        assertEquals(1, statementsOf("CCPTAB.sql").size());
        assertEquals(11, statementsOf("CSL501.sql").size());
        assertEquals(3, statementsOf("CSL502.spufi").size());
    }

    /** A script is the file, so its statements belong to no program and its node says SQL. */
    @Test
    void statementsOfAScriptCarryNoProgram() {
        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            SourceRecord source = dao.findAllSources().stream()
                    .filter(row -> row.path().equals("CCPTAB.sql")).findFirst().orElseThrow();
            NodeRecord node = dao.findNode(source.id()).orElseThrow();
            assertEquals("SQL", node.type());
            assertEquals("CCPTAB.sql", node.label());
            for (SqlStatementRecord statement : dao.findSqlStatementsBySource(source.id())) {
                assertNull(statement.programId(), "スクリプトの文はプログラムに属さないこと");
                assertEquals("CCPTAB.sql", statement.file());
            }
        }
    }

    /** The line a statement is reported at is the line its own SQL starts on, not the banner's. */
    @Test
    void aStatementIsReportedAtTheLineItsSqlStartsOn() {
        SqlStatementRecord create = statementsOf("CCPTAB.sql").get(0);
        assertEquals("DDL", create.kind());
        assertEquals(11, create.line());
        assertEquals(15, create.endLine());
        // The SPUFI member is a card image, so its sequence number must not open the next statement.
        List<SqlStatementRecord> spufi = statementsOf("CSL502.spufi");
        assertEquals(List.of(2, 5, 10), spufi.stream().map(SqlStatementRecord::line).toList());
        assertEquals(List.of("SELECT", "SELECT", "UPDATE"),
                spufi.stream().map(SqlStatementRecord::kind).toList());
    }

    /** A CREATE TABLE declares the table: the node says how many columns and which script. */
    @Test
    void createTableCarriesItsColumnCountAndTheScriptThatDeclaresIt() {
        CallGraphNode table = nodeOf("db2:CCPDB.CCPTAB");
        assertEquals(NodeKind.DB2_TABLE, table.kind());
        assertEquals("2", table.attributes().get("columns"));
        assertEquals("CCPTAB.sql", table.attributes().get("definedIn"));
        CallGraphNode koza = nodeOf("db2:CSDB.CSQKOZA");
        assertEquals("6", koza.attributes().get("columns"));
        assertEquals("CSL501.sql", koza.attributes().get("definedIn"));
    }

    /** A procedure is a program of its own, named without its schema qualifier. */
    @Test
    void aProcedureBecomesAProgramNodeWithTheTablesItsBodyTouches() {
        CallGraphNode procedure = nodeOf("sqlroutine:CSQ_UPD_ZANDAKA");
        assertEquals(NodeKind.PROGRAM, procedure.kind());
        assertEquals("true", procedure.attributes().get("sqlProcedure"));
        assertEquals("CSL501.sql", procedure.attributes().get("definedIn"));
        assertEquals(Map.of("db2:CSDB.CSQKOZA", "RU"),
                accessByTarget("sqlroutine:CSQ_UPD_ZANDAKA"),
                "本体が読み書きする表を CRUD の文字で持つこと");
    }

    /**
     * A declaration the Db2 grammar refuses still names its table, so the table is in the graph with
     * the script that declares it. What is missing is the column count, and a count of zero would
     * read as a table declared without columns.
     */
    @Test
    void aDeclarationTheGrammarRefusedStillNamesItsTable() {
        CallGraphNode table = nodeOf("db2:CSDB.CSQ_MEISAI");
        assertEquals(NodeKind.DB2_TABLE, table.kind());
        assertEquals("CSL501.sql", table.attributes().get("definedIn"));
        assertNull(table.attributes().get("columns"),
                "読めなかった宣言は列数を持たないこと");
    }

    /** A function is a routine like any other, and carries the script that defines it. */
    @Test
    void aFunctionBecomesARoutineNode() {
        CallGraphNode function = nodeOf("sqlroutine:CSQ_RISOKU");
        assertEquals(NodeKind.PROGRAM, function.kind());
        assertEquals("true", function.attributes().get("sqlProcedure"));
        assertEquals("CSL501.sql", function.attributes().get("definedIn"));
    }

    /** A statement the grammar read only in part is reported where it stands, as a warning. */
    @Test
    void aStatementReadOnlyInPartIsReportedAtItsOwnLine() {
        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            SourceRecord source = dao.findAllSources().stream()
                    .filter(row -> row.path().equals("CSL501.sql")).findFirst().orElseThrow();
            List<FindingRecord> degraded = dao.findFindingsBySource(source.id()).stream()
                    .filter(finding -> "sql-syntax".equals(finding.ruleId())).toList();
            assertEquals(List.of(17, 58),
                    degraded.stream().map(FindingRecord::startLine).sorted().toList());
            assertEquals(List.of("WARNING", "WARNING"),
                    degraded.stream().map(FindingRecord::level).toList());
        }
    }

    /** A trigger is read the same way: its body inserts into the audit table. */
    @Test
    void aTriggerBecomesAProgramNodeToo() {
        CallGraphNode trigger = nodeOf("sqlroutine:CSQ_TRG_ZANKOU");
        assertEquals("true", trigger.attributes().get("sqlProcedure"));
        assertEquals(Map.of("db2:CSDB.CSQ_AUDIT", "C"), accessByTarget("sqlroutine:CSQ_TRG_ZANKOU"));
    }

    /** The DML a SPUFI member runs is nobody's call, so it contributes no edge. */
    @Test
    void spufiStatementsContributeNoEdge() {
        assertTrue(outcome.callGraph().edges().stream().noneMatch(edge ->
                        edge.toId().equals("db2:CSDB.CSQKOZA")
                                && !edge.fromId().startsWith("sqlroutine:")),
                "SPUFI の文は辺を作らないこと: " + outcome.callGraph().toJson());
    }

    /**
     * The statements of a script reach the rules the way a program's do: both stand in the one list
     * the analysis context is built from, so a CREATE TABLE a script holds is the column list R057
     * looks a column name up in.
     */
    @Test
    void theDeclarationAScriptHoldsReachesTheRules() {
        SourceSet s = Pipelines.lint(DDL, List.of(), Map.of(), RuleSet.load((Path) null));
        SqlStatementModel declaration = s.sql().stream()
                .filter(statement -> statement.declaredTable()
                        .filter("CSDB.CSQKOZA"::equals).isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("スクリプトの CREATE TABLE を規則が読めること"));
        assertEquals(6, declaration.declaredColumns().size());
    }

    /** The reference edges of one node, target to the CRUD letters the edge carries. */
    private static Map<String, String> accessByTarget(String fromId) {
        return outcome.callGraph().edges().stream()
                .filter(edge -> edge.fromId().equals(fromId) && edge.kind() == EdgeKind.REFERENCE)
                .collect(Collectors.toMap(CallGraphEdge::toId,
                        edge -> String.valueOf(edge.attributes().get("access"))));
    }
}
