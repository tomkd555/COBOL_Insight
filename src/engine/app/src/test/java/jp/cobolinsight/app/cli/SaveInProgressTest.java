package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.pipeline.Pipelines;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

/**
 * The temporary file of a {@code save} in progress, or one a killed save left behind, sits beside
 * the original in the asset folder. A scan and a lint over the folder write what they write
 * without it.
 */
class SaveInProgressTest {

    private static final Path SAMPLES =
            Path.of("..", "..", "..", "samples", "seeded").toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    @Test
    void scanAndLintGiveTheSameResultsWithASaveInProgress() throws Exception {
        Path assets = tempDir.resolve("assets");
        for (String dir : List.of("cobol", "copybook", "jcl")) {
            copyDirectory(SAMPLES.resolve(dir), assets.resolve(dir));
        }
        Map<String, List<String>> scanBefore = scan(assets, "before");
        List<String> lintBefore = lint(assets, "before");

        Path program = SaveCommand.createTemp(assets.resolve("cobol").resolve("SYK001.cbl"));
        Files.copy(assets.resolve("cobol").resolve("SYK001.cbl"), program,
                StandardCopyOption.REPLACE_EXISTING);
        Path copybook = SaveCommand.createTemp(assets.resolve("copybook").resolve("SYKCPY1.cpy"));
        assertEquals(assets.resolve("cobol"), program.getParent(), "beside the original");
        assertTrue(program.getFileName().toString().startsWith(".SYK001.cbl"), program.toString());
        assertTrue(Files.isRegularFile(copybook) && Files.size(copybook) == 0, copybook.toString());

        assertEquals(scanBefore, scan(assets, "during"));
        assertEquals(lintBefore, lint(assets, "during"));
    }

    /** Every row of every table, each table's rows sorted. */
    private Map<String, List<String>> scan(Path assets, String name) throws SQLException {
        Path databaseFile = tempDir.resolve(name + ".db");
        Pipelines.scan(assets, databaseFile, List.of(), Map.of());
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
            for (String table : names) {
                List<String> rows = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery("SELECT * FROM " + table)) {
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
                tables.put(table, rows);
            }
        }
        return tables;
    }

    /** The two SARIF files a lint over the folder writes. */
    private List<String> lint(Path assets, String name) throws IOException {
        Path sarif = tempDir.resolve(name + ".sarif");
        Path sqlSarif = tempDir.resolve(name + "-sql.sarif");
        new CommandLine(new Main()).execute("lint", assets.toString(), "--sarif",
                sarif.toString(), "--sql-sarif", sqlSarif.toString());
        return List.of(Files.readString(sarif, StandardCharsets.UTF_8),
                Files.readString(sqlSarif, StandardCharsets.UTF_8));
    }

    private static void copyDirectory(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> children = Files.list(from)) {
            for (Path child : children.filter(Files::isRegularFile).toList()) {
                Files.copy(child, to.resolve(child.getFileName().toString()));
            }
        }
    }
}
