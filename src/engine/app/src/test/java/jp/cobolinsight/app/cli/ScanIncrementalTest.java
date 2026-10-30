package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.ScanOutcome;
import jp.cobolinsight.app.pipeline.Persist;
import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies incremental analysis driven by content hashing (only changed members are reanalyzed). */
class ScanIncrementalTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples", "seeded").toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    @Test
    void onlyChangedMembersAndTheirDependentsAreReanalyzed() throws IOException {
        Path assets = tempDir.resolve("assets");
        for (String dir : List.of("bms", "cobol", "copybook", "jcl")) {
            copyDirectory(SAMPLES.resolve(dir), assets.resolve(dir));
        }
        Path databaseFile = tempDir.resolve("scan.db");

        ScanOutcome.Summary first = Pipelines.scan(assets, databaseFile,
                List.of(assets.resolve("copybook")), Map.of()).summary();
        assertEquals(19, first.analyzed().size());
        assertEquals(0, first.exitCode());

        // A copybook change includes the importing programs (SYK001-SYK003) in the reanalysis scope
        appendCommentLine(assets.resolve("copybook").resolve("SYKCPY1.cpy"));
        ScanOutcome.Summary second = Pipelines.scan(assets, databaseFile,
                List.of(assets.resolve("copybook")), Map.of()).summary();
        assertEquals(List.of("cobol/SYK001.cbl", "cobol/SYK002.cbl", "cobol/SYK003.cbl",
                "copybook/SYKCPY1.cpy"), second.analyzed().stream().sorted().toList());
        assertEquals(15, second.skipped().size());
        assertEquals(0, second.exitCode());
        assertEdgeCounts(databaseFile, 6, 9);

        // A program change includes the JCL that calls it (SYKD010, SYKD030) in the reanalysis scope
        appendCommentLine(assets.resolve("cobol").resolve("SYK001.cbl"));
        ScanOutcome.Summary third = Pipelines.scan(assets, databaseFile,
                List.of(assets.resolve("copybook")), Map.of()).summary();
        assertEquals(List.of("cobol/SYK001.cbl", "jcl/SYKD010.jcl", "jcl/SYKD030.jcl"),
                third.analyzed().stream().sorted().toList());
        assertEquals(0, third.exitCode());
        assertEdgeCounts(databaseFile, 6, 9);

        // A deleted source disappears along with its rows and node
        Files.delete(assets.resolve("jcl").resolve("SYKD030.jcl"));
        ScanOutcome.Summary fourth = Pipelines.scan(assets, databaseFile,
                List.of(assets.resolve("copybook")), Map.of()).summary();
        assertEquals(List.of("jcl/SYKD030.jcl"), fourth.removed());
        assertEquals(List.of(), fourth.analyzed());
        assertEdgeCounts(databaseFile, 6, 7);
        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            assertEquals(18, new PersistenceDao(database.connection()).findAllSources().size());
        }
    }

    /**
     * A scan that takes unchanged assets back from the parse cache writes the project file and the
     * COPY expansion table a full scan of the same folder writes: after one program changed, after
     * nothing changed, and after a copybook changed. It compares what the two scans write, rows
     * and expansions; that the models the cache gives back equal a fresh parse is pinned by
     * ParseCacheTest (aResultComesBackAsAFreshParseGivesItUntilAnInputChanges and
     * whatAnAssetReportedComesBackAsAFreshParseGivesIt).
     */
    @Test
    void aScanFromTheParseCacheWritesWhatAFullScanWrites() throws Exception {
        Path assets = tempDir.resolve("assets");
        for (String dir : List.of("bms", "cobol", "copybook", "jcl")) {
            copyDirectory(SAMPLES.resolve(dir), assets.resolve(dir));
        }
        Files.createDirectories(assets.resolve("sql"));
        Files.writeString(assets.resolve("sql").resolve("ROUTINES.sql"), String.join("\n",
                "--#SET TERMINATOR @",
                "CREATE PROCEDURE CSDB.P LANGUAGE SQL",
                "    BEGIN",
                "        UPDATE CSDB.CSQKOZA SET ZANDAKA = 0;",
                "    END@",
                "SELECT ZANDAKA FROM CSDB.CSQKOZA WHERE@",
                ""), StandardCharsets.UTF_8);
        List<Path> copybooks = List.of(assets.resolve("copybook"));
        Path databaseFile = tempDir.resolve("incremental").resolve("scan.db");
        Files.createDirectories(databaseFile.getParent());
        Pipelines.scan(assets, databaseFile, copybooks, Map.of());
        assertTrue(Files.isRegularFile(databaseFile.resolveSibling("scan.db.parse-cache")));

        int round = 0;
        for (Path changed : new Path[] {assets.resolve("cobol").resolve("SYK002.cbl"), null,
                assets.resolve("copybook").resolve("SYKCPY1.cpy")}) {
            if (changed != null) {
                appendCommentLine(changed);
            }
            String incremental = Pipelines.scan(assets, databaseFile, copybooks, Map.of())
                    .copyExpansions().toJson();
            Path fullFile = tempDir.resolve("full" + (++round)).resolve("scan.db");
            Files.createDirectories(fullFile.getParent());
            String full = Pipelines.scan(assets, fullFile, copybooks, Map.of())
                    .copyExpansions().toJson();

            assertEquals(full, incremental, "COPY expansion table, round " + round);
            assertEquals(contentOf(fullFile), contentOf(databaseFile), "round " + round);
        }
    }

    /**
     * A copybook reached through the copybook search path from outside the asset folder, copied
     * directly or by a nested COPY: after it changes, the programs copying it are written again,
     * and the project file holds what a full scan writes.
     */
    @Test
    void aChangeToACopybookOutsideTheAssetFolderRewritesTheProgramsCopyingIt() throws Exception {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("EXTPGM.cbl"), String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID.  EXTPGM.",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           COPY OUTER.",
                "           STOP RUN.",
                ""), StandardCharsets.UTF_8);
        Path external = tempDir.resolve("external");
        Files.createDirectories(external);
        Files.writeString(external.resolve("OUTER.cpy"), String.join("\n",
                "           CALL 'OUTERPGM'.",
                "           COPY INNER.",
                ""), StandardCharsets.UTF_8);
        Files.writeString(external.resolve("INNER.cpy"), "           CALL 'SUBA'.\n",
                StandardCharsets.UTF_8);
        List<Path> copybooks = List.of(external);
        Path databaseFile = tempDir.resolve("incremental").resolve("scan.db");
        Files.createDirectories(databaseFile.getParent());
        Pipelines.scan(assets, databaseFile, copybooks, Map.of());

        int round = 0;
        for (String copybook : List.of("INNER.cpy", "OUTER.cpy")) {
            Files.writeString(external.resolve(copybook), Files.readString(external.resolve(
                    copybook)).replace("'SUBA'", "'SUBB'").replace("'OUTERPGM'", "'OUTERB'"),
                    StandardCharsets.UTF_8);
            ScanOutcome.Summary summary = Pipelines.scan(assets, databaseFile, copybooks,
                    Map.of()).summary();
            Path fullFile = tempDir.resolve("full" + (++round)).resolve("scan.db");
            Files.createDirectories(fullFile.getParent());
            Pipelines.scan(assets, fullFile, copybooks, Map.of());

            assertEquals(List.of("EXTPGM.cbl"), summary.analyzed(), copybook);
            assertEquals(contentOf(fullFile), contentOf(databaseFile), copybook);
        }
        List<String> edges = contentOf(databaseFile).get("PARAGRAPH_EDGE");
        assertTrue(edges.stream().anyMatch(row -> row.contains("|SUBB|")), edges::toString);
    }

    /** Every row of every table, each table's rows sorted. */
    private static Map<String, List<String>> contentOf(Path databaseFile) throws SQLException {
        Map<String, List<String>> tables = new TreeMap<>();
        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile);
                Statement statement = database.connection().createStatement()) {
            List<String> names = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table'")) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
            for (String name : names) {
                List<String> rows = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery("SELECT * FROM " + name)) {
                    int columns = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        StringBuilder row = new StringBuilder();
                        for (int c = 1; c <= columns; c++) {
                            row.append(rs.getString(c)).append('|');
                        }
                        rows.add(row.toString());
                    }
                }
                rows.sort(null);
                tables.put(name, rows);
            }
        }
        return tables;
    }

    private static void assertEdgeCounts(Path databaseFile, int expectedCopy, int expectedExecution) {
        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            long copy = 0;
            long execution = 0;
            for (var source : dao.findAllSources()) {
                for (var edge : dao.findEdgesFrom(source.id())) {
                    // Excludes the call-graph layer (ID at or above the lower bound); counts only scan's incremental edges
                    if (edge.id() >= Persist.GRAPH_ID_BASE) {
                        continue;
                    }
                    if ("COPY".equals(edge.kind())) {
                        copy++;
                    } else if ("EXECUTION".equals(edge.kind())) {
                        execution++;
                    }
                }
            }
            assertEquals(expectedCopy, copy, "COPYエッジ数");
            assertEquals(expectedExecution, execution, "EXECUTIONエッジ数");
        }
    }

    private static void copyDirectory(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> children = Files.list(from)) {
            for (Path child : children.filter(Files::isRegularFile).toList()) {
                Files.copy(child, to.resolve(child.getFileName().toString()));
            }
        }
    }

    private static void appendCommentLine(Path file) throws IOException {
        Files.writeString(file, System.lineSeparator() + "      *TOUCH",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
}
