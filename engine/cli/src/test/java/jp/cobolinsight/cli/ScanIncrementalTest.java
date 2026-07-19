package jp.cobolinsight.cli;

import jp.cobolinsight.persistence.PersistenceDao;
import jp.cobolinsight.persistence.PersistenceDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 内容ハッシュによる増分解析の検証(05_開発計画.md §3.1: 変更メンバのみ再解析される)。 */
class ScanIncrementalTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    @Test
    void onlyChangedMembersAndTheirDependentsAreReanalyzed() throws IOException {
        Path assets = tempDir.resolve("assets");
        for (String dir : List.of("bms", "cobol", "copybook", "jcl")) {
            copyDirectory(SAMPLES.resolve(dir), assets.resolve(dir));
        }
        Path databaseFile = tempDir.resolve("scan.db");
        ScanRunner.Options options = new ScanRunner.Options(assets, databaseFile,
                List.of(assets.resolve("copybook")), Map.of());

        ScanRunner.Summary first = ScanRunner.run(options);
        assertEquals(16, first.analyzed().size());
        assertEquals(0, first.exitCode());

        // コピー句の変更は、取り込むプログラム(SYK001〜SYK003)だけを道連れに再解析する
        appendCommentLine(assets.resolve("copybook").resolve("SYKCPY1.cpy"));
        ScanRunner.Summary second = ScanRunner.run(options);
        assertEquals(List.of("cobol/SYK001.cbl", "cobol/SYK002.cbl", "cobol/SYK003.cbl",
                "copybook/SYKCPY1.cpy"), second.analyzed().stream().sorted().toList());
        assertEquals(12, second.skipped().size());
        assertEquals(0, second.exitCode());
        assertEdgeCounts(databaseFile, 6, 6);

        // プログラムの変更は、それを呼ぶJCL(SYKD010・SYKD030)だけを道連れに再解析する
        appendCommentLine(assets.resolve("cobol").resolve("SYK001.cbl"));
        ScanRunner.Summary third = ScanRunner.run(options);
        assertEquals(List.of("cobol/SYK001.cbl", "jcl/SYKD010.jcl", "jcl/SYKD030.jcl"),
                third.analyzed().stream().sorted().toList());
        assertEquals(0, third.exitCode());
        assertEdgeCounts(databaseFile, 6, 6);

        // 削除されたソースは行とノードごと消える
        Files.delete(assets.resolve("jcl").resolve("SYKD030.jcl"));
        ScanRunner.Summary fourth = ScanRunner.run(options);
        assertEquals(List.of("jcl/SYKD030.jcl"), fourth.removed());
        assertEquals(List.of(), fourth.analyzed());
        assertEdgeCounts(databaseFile, 6, 4);
        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            assertEquals(15, new PersistenceDao(database.connection()).findAllSources().size());
        }
    }

    private static void assertEdgeCounts(Path databaseFile, int expectedCopy, int expectedExecution) {
        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            long copy = 0;
            long execution = 0;
            for (var source : dao.findAllSources()) {
                for (var edge : dao.findEdgesFrom(source.id())) {
                    // M2の呼出関係グラフ層(ID下限以上)は対象外。scanの増分用エッジのみ数える
                    if (edge.id() >= ScanRunner.GRAPH_ID_BASE) {
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
