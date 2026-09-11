package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.FindingRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import jp.cobolinsight.app.persistence.model.SqlStatementRecord;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.sql.SqlAnalysis;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;
import jp.cobolinsight.rules.RuleSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A statement the SQL grammar will not take in full costs the program nothing: the model still
 * reaches the rules through {@code sqlByPath}, and the pipeline says so with one {@code sql-syntax}
 * warning at the statement's own line.
 */
class DegradedSqlFindingTest {

    /** Only there because {@link SourceSet.Options} takes a database path; no step here writes one. */
    private static final Path UNUSED_DATABASE =
            Path.of(System.getProperty("java.io.tmpdir"), "cobol-insight-degraded-sql.db");

    /**
     * A subscripted host variable: Che4z reads the program, because the item it names has an
     * OCCURS, and the Db2 grammar behind {@code sql-frontend} will not take the subscript. That
     * is the shape this test is about — a statement the tool holds only in part, not a program
     * with a defect in it.
     */
    private static final String PROGRAM = """
                   IDENTIFICATION DIVISION.
                   PROGRAM-ID. DEGRSQL.
                   DATA DIVISION.
                   WORKING-STORAGE SECTION.
                   01  WS-KENSU               PIC S9(9) COMP.
                   01  WS-CD-AREA.
                       05  WS-CD-TBL          PIC X(08) OCCURS 5 TIMES.
                   PROCEDURE DIVISION.
                   MAIN-PARA.
                       EXEC SQL
                           SELECT COUNT(*) INTO :WS-KENSU FROM SYKDB.ZAIKOM
                           WHERE SHOHIN_CD = :WS-CD-TBL(1)
                       END-EXEC.
                       GOBACK.
            """;

    /**
     * A degraded statement in a member two programs copy. A multi-row INSERT this time: Che4z
     * reads {@code FOR n ROWS} where the Db2 grammar behind sql-frontend does not, and unlike a
     * subscript Che4z takes it inside a copybook as well.
     */
    private static final String MEMBER = """
                       EXEC SQL
                           INSERT INTO SYKDB.ZAIKOM (SHOHIN_CD, ZAIKO_SU)
                           VALUES (:WS-CD-TBL, :WS-KENSU)
                           FOR 3 ROWS
                       END-EXEC.
            """;

    /** A program whose PROCEDURE DIVISION is the member and nothing else. */
    private static String caller(String programId) {
        return """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. %s.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                       01  WS-KENSU               PIC S9(9) COMP.
                       01  WS-CD-AREA.
                           05  WS-CD-TBL          PIC X(08) OCCURS 5 TIMES.
                       PROCEDURE DIVISION.
                       MAIN-PARA.
                           COPY DEGRINC.
                           GOBACK.
                """.formatted(programId);
    }

    private static SourceSet run(Path dir, List<Path> copybookPaths) {
        Set<AssetKind> kinds = EnumSet.allOf(AssetKind.class);
        SourceSet s = new SourceSet(new SourceSet.Options(dir, UNUSED_DATABASE, copybookPaths,
                Map.of(), RuleSet.load((Path) null), Set.of(Needs.SQL)));
        Pipeline.run(List.of(new Discover(), new Classify(kinds, false), new Decode(kinds),
                new Parse(), new Semantic()), s);
        return s;
    }

    private static SourceSet run(Path dir) throws IOException {
        Files.writeString(dir.resolve("DEGRSQL.cbl"), PROGRAM, StandardCharsets.UTF_8);
        return run(dir, List.of());
    }

    private static List<Finding> sqlSyntaxFindings(SourceSet s, String relPath) {
        return s.findingsOf(relPath).stream()
                .filter(f -> Finding.SQL_SYNTAX_RULE_ID.equals(f.ruleId())).toList();
    }

    @Test
    void aDegradedStatementStaysInTheModelAndIsReportedAsAWarning(@TempDir Path dir)
            throws IOException {
        SourceSet s = run(dir);

        List<SqlStatementModel> statements = s.sqlByPath().get("DEGRSQL.cbl");
        assertEquals(1, statements.size(),
                () -> "a degraded statement stays in the model: " + s.sqlByPath());
        SqlStatementModel statement = statements.get(0);
        assertEquals(SqlAnalysis.DEGRADED, statement.analysis());
        assertEquals(SqlStatementKind.SELECT_INTO, statement.kind());
        assertEquals(List.of("SYKDB.ZAIKOM"), statement.referencedTables());

        List<Finding> findings = sqlSyntaxFindings(s, "DEGRSQL.cbl");
        assertEquals(1, findings.size(),
                () -> "one sql-syntax finding: " + s.findingsOf("DEGRSQL.cbl"));
        Finding finding = findings.get(0);
        assertEquals(FindingLevel.WARNING, finding.level());
        assertEquals(statement.range().start().line(), finding.location().line());
        assertTrue(finding.message().startsWith("SQL 文を完全には解析できませんでした"),
                finding.message());
        assertTrue(finding.message().endsWith("文の種別と表名だけを使います。"), finding.message());
    }

    @Test
    void aStatementInAMemberIsReportedAgainstTheMemberOnce(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("DEGRINC.cpy"), MEMBER, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("CALLER1.cbl"), caller("CALLER1"), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("CALLER2.cbl"), caller("CALLER2"), StandardCharsets.UTF_8);

        SourceSet s = run(dir, List.of(dir));

        assertEquals(1, s.sqlByPath().get("CALLER1.cbl").size(), () -> s.sqlByPath().toString());
        assertEquals(1, s.sqlByPath().get("CALLER2.cbl").size(), () -> s.sqlByPath().toString());
        assertEquals(List.of(), sqlSyntaxFindings(s, "CALLER1.cbl"),
                "the warning belongs to the member, not to a program that copies it");
        assertEquals(List.of(), sqlSyntaxFindings(s, "CALLER2.cbl"),
                "the warning belongs to the member, not to a program that copies it");

        List<Finding> findings = sqlSyntaxFindings(s, "DEGRINC.cpy");
        assertEquals(1, findings.size(),
                () -> "one warning however many programs copy the member: " + findings);
        assertTrue(findings.get(0).location().file().endsWith("DEGRINC.cpy"),
                "the finding is positioned in the member: " + findings.get(0).location().file());
    }

    /**
     * Saying that a statement was read only in part is the pipeline's obligation, not a rule's, so
     * {@code lint} must keep reporting it when {@code rules.json} turns every rule off.
     */
    @Test
    void lintReportsADegradedStatementWithEveryRuleDisabled(@TempDir Path dir) throws IOException {
        Path assets = Files.createDirectories(dir.resolve("assets"));
        Files.writeString(assets.resolve("DEGRSQL.cbl"), PROGRAM, StandardCharsets.UTF_8);
        StringBuilder json = new StringBuilder("{\"version\":2,\"rules\":{");
        String separator = "";
        for (RuleSet.RuleEntry entry : RuleSet.load((Path) null).catalogue()) {
            json.append(separator).append('"').append(entry.id()).append("\":{\"enabled\":false}");
            separator = ",";
        }
        Path rulesFile = dir.resolve("rules.json");
        Files.writeString(rulesFile, json.append("}}").toString(), StandardCharsets.UTF_8);

        SourceSet s = Pipelines.lint(assets, List.of(), Map.of(), RuleSet.load(rulesFile));

        assertEquals(1, sqlSyntaxFindings(s, "DEGRSQL.cbl").size(),
                () -> "one sql-syntax finding: " + s.findingsOf("DEGRSQL.cbl"));
    }

    /**
     * The same member through a whole scan. A statement a program copies carries the member's lines,
     * so its row has to name the member: reading those lines against the program would point at
     * whatever text happens to stand there.
     */
    @Test
    void theRowOfACopiedStatementNamesTheMemberItStandsIn(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("DEGRINC.cpy"), MEMBER, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("CALLER1.cbl"), caller("CALLER1"), StandardCharsets.UTF_8);
        Path databaseFile = dir.resolve("scan.db");

        Pipelines.scan(dir, databaseFile, List.of(dir), Map.of());

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            SourceRecord caller = dao.findAllSources().stream()
                    .filter(row -> row.path().equals("CALLER1.cbl")).findFirst().orElseThrow();
            List<SqlStatementRecord> statements = dao.findSqlStatementsBySource(caller.id());
            assertEquals(1, statements.size(), () -> statements.toString());
            assertEquals("DEGRINC.cpy", statements.get(0).file(),
                    "コピー句から取り込んだ文の行はコピー句の行であること");
        }
    }

    /**
     * The member's own bytes do not change when a program that copies it is added, so an
     * incremental scan would skip it — and the warning, which belongs to the member, would never
     * be written. Adding the program has to bring the member back into the rewritten set.
     */
    @Test
    void theWarningIsWrittenWhenAProgramCopyingTheMemberIsAdded(@TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("DEGRINC.cpy"), MEMBER, StandardCharsets.UTF_8);
        Path databaseFile = dir.resolve("scan.db");
        Pipelines.scan(dir, databaseFile, List.of(dir), Map.of());
        assertEquals(List.of(), memberRuleIds(databaseFile),
                "no program copies the member yet, so there is no block and no warning");

        Files.writeString(dir.resolve("CALLER1.cbl"), caller("CALLER1"), StandardCharsets.UTF_8);
        Pipelines.scan(dir, databaseFile, List.of(dir), Map.of());

        assertEquals(List.of(Finding.SQL_SYNTAX_RULE_ID), memberRuleIds(databaseFile),
                "コピー句に立つ SQL 文の警告が保存されること");
    }

    private static List<String> memberRuleIds(Path databaseFile) {
        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            SourceRecord member = dao.findAllSources().stream()
                    .filter(row -> row.path().equals("DEGRINC.cpy")).findFirst().orElseThrow();
            return dao.findFindingsBySource(member.id()).stream()
                    .map(FindingRecord::ruleId).toList();
        }
    }
}
