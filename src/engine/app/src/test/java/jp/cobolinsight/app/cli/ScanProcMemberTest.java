package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.persistence.IncrementalAnalysisPlanner;
import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.CallEdgeRecord;
import jp.cobolinsight.app.persistence.model.FindingRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import jp.cobolinsight.app.pipeline.Paths;
import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.ScanOutcome;
import jp.cobolinsight.rules.RuleSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a PROC member reaches the job that expands it: the INCLUDE edge that records the expansion,
 * the reanalysis that edge buys, and the {@code --proc-path} directories searched on top of the
 * asset folder. samples-field/batch is the fixture: FLJ010 and FLJ020 both expand FLP010, whose
 * first step runs FLB010.
 */
class ScanProcMemberTest {

    private static final Path BATCH =
            Path.of("..", "..", "..", "samples-field", "batch").toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    /** Copies the batch fixture, leaving out the files named. */
    private Path copyBatch(String directory, String... without) throws IOException {
        Set<String> excluded = Set.of(without);
        Path assets = tempDir.resolve(directory);
        Files.createDirectories(assets);
        try (Stream<Path> children = Files.list(BATCH)) {
            for (Path child : children.filter(Files::isRegularFile).toList()) {
                String name = child.getFileName().toString();
                if (!excluded.contains(name)) {
                    Files.copy(child, assets.resolve(name));
                }
            }
        }
        return assets;
    }

    private static long sourceId(PersistenceDao dao, Path assets, String relPath) {
        return dao.findSourceByPath(Paths.rootOf(assets), relPath)
                .map(SourceRecord::id).orElseThrow();
    }

    private static Set<Long> executionTargets(PersistenceDao dao, long jobId) {
        return dao.findEdgesFrom(jobId).stream()
                .filter(e -> IncrementalAnalysisPlanner.EXECUTION_EDGE_KIND.equals(e.kind()))
                .map(CallEdgeRecord::toNode).collect(Collectors.toSet());
    }

    @Test
    void theMemberExpandedIntoAJobBecomesAnIncludeEdge() throws IOException {
        Path assets = copyBatch("with-proc");
        Path databaseFile = tempDir.resolve("with-proc.db");

        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            long member = sourceId(dao, assets, "FLP010.proc");
            long job = sourceId(dao, assets, "FLJ010.jcl");
            // FLJ010 and FLJ020 both expand FLP010; one edge apiece and none from anywhere else.
            assertEquals(Set.of(job, sourceId(dao, assets, "FLJ020.jcl")),
                    dao.findEdgesFrom(member).stream()
                            .filter(e -> IncrementalAnalysisPlanner.INCLUDE_EDGE_KIND
                                    .equals(e.kind()))
                            .map(CallEdgeRecord::toNode).collect(Collectors.toSet()));
            assertTrue(executionTargets(dao, job).contains(sourceId(dao, assets, "FLB010.cbl")),
                    "the PROC's own step runs FLB010");
        }
    }

    @Test
    void editingTheMemberReanalysesTheJobThatExpandsIt() throws IOException {
        Path assets = copyBatch("edited-proc");
        Path databaseFile = tempDir.resolve("edited-proc.db");
        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());

        Path member = assets.resolve("FLP010.proc");
        Files.writeString(member, Files.readString(member, StandardCharsets.UTF_8)
                .replace("//*  日次入金ファイルを編集して出力します。",
                        "//*  日次入金ファイルを編集して出力します(改訂)。"),
                StandardCharsets.UTF_8);
        ScanOutcome.Summary summary =
                Pipelines.scan(assets, databaseFile, List.of(assets), Map.of()).summary();

        assertTrue(summary.analyzed().contains("FLJ010.jcl"),
                () -> "PROC を直したら展開する側も解析し直すこと: " + summary.analyzed());
    }

    /**
     * A member reached through {@code --proc-path} has neither a source row nor a hash of its own,
     * so its digest is folded into the hash of every job that expands it. Editing it has to bring
     * those jobs back into the analysis, exactly as editing a member inside the folder does.
     */
    @Test
    void editingAMemberOutsideTheAssetFolderReanalysesTheJobThatExpandsIt() throws IOException {
        Path assets = copyBatch("outside-proc", "FLP010.proc");
        Path library = tempDir.resolve("outside-proclib");
        Files.createDirectories(library);
        Path member = library.resolve("FLP010.proc");
        Files.copy(BATCH.resolve("FLP010.proc"), member);
        Path databaseFile = tempDir.resolve("outside-proc.db");
        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of(),
                RuleSet.load((Path) null), List.of(library));

        Files.writeString(member, Files.readString(member, StandardCharsets.UTF_8)
                .replace("//*  日次入金ファイルを編集して出力します。",
                        "//*  日次入金ファイルを編集して出力します(改訂)。"),
                StandardCharsets.UTF_8);
        ScanOutcome.Summary summary = Pipelines.scan(assets, databaseFile, List.of(assets),
                Map.of(), RuleSet.load((Path) null), List.of(library)).summary();

        assertTrue(summary.analyzed().contains("FLJ010.jcl"),
                () -> "資産フォルダ外の PROC を直したら展開する側も解析し直すこと: "
                        + summary.analyzed());
    }

    /** Two directories may hold a member of the same name; a job takes the one beside it. */
    @Test
    void theMemberBesideTheJobWinsOverOneInAnotherDirectory() throws IOException {
        Path assets = copyBatch("own-directory");
        Path other = assets.resolve("other");
        Files.createDirectories(other);
        Files.writeString(other.resolve("FLP010.proc"), String.join("\n",
                "//FLP010 PROC CYCLE=000000,HLQ=FLW",
                "//OTHER    EXEC PGM=FLB060",
                "//         PEND",
                ""), StandardCharsets.UTF_8);
        Path databaseFile = tempDir.resolve("own-directory.db");

        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            long job = sourceId(dao, assets, "FLJ010.jcl");
            assertTrue(executionTargets(dao, job).contains(sourceId(dao, assets, "FLB010.cbl")),
                    "the PROC beside the job runs FLB010");
            assertFalse(executionTargets(dao, job).contains(sourceId(dao, assets, "FLB060.cbl")),
                    "the PROC of the other directory is not the one the job called");
        }
    }

    /** A member named FLP010.inc is the member FLP010, whatever suffix it was filed under. */
    @Test
    void findsAMemberFiledUnderAnyOfTheMemberSuffixes() throws IOException {
        Path assets = copyBatch("inc-suffix", "FLP010.proc");
        Files.copy(BATCH.resolve("FLP010.proc"), assets.resolve("FLP010.inc"));
        Path databaseFile = tempDir.resolve("inc-suffix.db");

        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            assertTrue(executionTargets(dao, sourceId(dao, assets, "FLJ010.jcl"))
                            .contains(sourceId(dao, assets, "FLB010.cbl")),
                    "FLP010.inc answers to EXEC FLP010");
        }
    }

    /** The member file may be filed in another case than the JCL writes the name in. */
    @Test
    void findsAMemberFiledUnderAnotherCase() throws IOException {
        Path assets = copyBatch("other-case", "FLP010.proc");
        Files.copy(BATCH.resolve("FLP010.proc"), assets.resolve("flp010.proc"));
        Path databaseFile = tempDir.resolve("other-case.db");

        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            assertTrue(executionTargets(dao, sourceId(dao, assets, "FLJ010.jcl"))
                            .contains(sourceId(dao, assets, "FLB010.cbl")),
                    "flp010.proc answers to EXEC FLP010");
        }
    }

    @Test
    void procPathAddsADirectoryOutsideTheAssetFolder() throws IOException {
        Path assets = copyBatch("no-proc", "FLP010.proc");
        Path library = tempDir.resolve("proclib");
        Files.createDirectories(library);
        Files.copy(BATCH.resolve("FLP010.proc"), library.resolve("FLP010.proc"));
        Path withoutOption = tempDir.resolve("no-proc.db");
        Path withOption = tempDir.resolve("with-option.db");

        assertEquals(0, new CommandLine(new Main()).execute("scan", assets.toString(),
                "--db", withoutOption.toString(), "--copybook-path", assets.toString()));
        assertEquals(0, new CommandLine(new Main()).execute("scan", assets.toString(),
                "--db", withOption.toString(), "--copybook-path", assets.toString(),
                "--proc-path", library.toString()));

        try (PersistenceDatabase database = PersistenceDatabase.open(withoutOption)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            assertFalse(executionTargets(dao, sourceId(dao, assets, "FLJ010.jcl"))
                            .contains(sourceId(dao, assets, "FLB010.cbl")),
                    "with the member out of reach the PROC's steps are unknown");
        }
        try (PersistenceDatabase database = PersistenceDatabase.open(withOption)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            long job = sourceId(dao, assets, "FLJ010.jcl");
            assertTrue(executionTargets(dao, job).contains(sourceId(dao, assets, "FLB010.cbl")),
                    "--proc-path finds the member and the PROC's steps come back");
            assertEquals(Set.of(), dao.findEdgesFrom(job).stream()
                            .filter(e -> IncrementalAnalysisPlanner.INCLUDE_EDGE_KIND
                                    .equals(e.kind()))
                            .map(CallEdgeRecord::toNode).collect(Collectors.toSet()),
                    "a member outside the asset folder has no source row, so it draws no edge");
        }
    }

    /** The member's own text with one statement no rule of the grammar reads. */
    private static String brokenMember() throws IOException {
        return Files.readString(BATCH.resolve("FLP010.proc"), StandardCharsets.UTF_8)
                .replace("//STEP2    EXEC PGM=FLB040,COND=(0,NE,STEP1)",
                        "//BADSTMT  FROBNICATE ALL");
    }

    private static List<String> syntaxMessages(PersistenceDao dao, Path assets, String... paths) {
        return Stream.of(paths)
                .flatMap(path -> dao.findFindingsBySource(sourceId(dao, assets, path)).stream())
                .filter(f -> "jcl-syntax".equals(f.ruleId()))
                .map(FindingRecord::message).toList();
    }

    /** A member two jobs expand is reported against its own source, once for the whole run. */
    @Test
    void writesAMemberDiagnosticOnceHoweverManyJobsExpandTheMember() throws IOException {
        Path assets = copyBatch("broken-proc", "FLP010.proc");
        Files.writeString(assets.resolve("FLP010.proc"), brokenMember(), StandardCharsets.UTF_8);
        Path databaseFile = tempDir.resolve("broken-proc.db");

        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            List<String> messages = syntaxMessages(dao, assets, "FLP010.proc");
            assertEquals(1, messages.size(),
                    () -> "FLJ010 and FLJ020 both expand it, and it is written once: " + messages);
            assertTrue(messages.get(0).startsWith("22 行の"), messages.get(0));
            assertEquals(List.of(), syntaxMessages(dao, assets, "FLJ010.jcl", "FLJ020.jcl"),
                    "the member's line belongs to the member, not to the jobs that expand it");
        }
    }

    /**
     * A member no job of the folder expands is read for its own sake, so the syntax errors it
     * holds are reported against it rather than going unsaid.
     */
    @Test
    void readsAMemberNoJobExpandsForItsOwnDiagnostics() throws IOException {
        Path assets = copyBatch("lonely-proc", "FLJ010.jcl", "FLJ020.jcl", "FLJ030.jcl");
        Files.writeString(assets.resolve("FLP010.proc"), brokenMember(), StandardCharsets.UTF_8);
        Path databaseFile = tempDir.resolve("lonely-proc.db");

        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            List<String> messages = syntaxMessages(dao, assets, "FLP010.proc");
            assertEquals(1, messages.size(), () -> "no job calls it, so it is read once: " + messages);
            assertTrue(messages.get(0).startsWith("22 行の"), messages.get(0));
        }
    }

    /**
     * A member outside the asset folder has no source row of its own, so its diagnostic falls back
     * to the job that expanded it. The message names the member file, without which the row would
     * carry the member's line against the job's own text.
     */
    @Test
    void namesTheMemberFileWhenTheDiagnosticFallsBackToTheJob() throws IOException {
        Path assets = copyBatch("broken-proclib-assets", "FLP010.proc");
        Path library = tempDir.resolve("broken-proclib");
        Files.createDirectories(library);
        Files.writeString(library.resolve("FLP010.proc"), brokenMember(), StandardCharsets.UTF_8);
        Path databaseFile = tempDir.resolve("broken-proclib.db");

        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of(),
                RuleSet.load((Path) null), List.of(library));

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            for (String job : List.of("FLJ010.jcl", "FLJ020.jcl")) {
                List<String> messages = syntaxMessages(dao, assets, job);
                assertEquals(1, messages.size(), () -> job + ": " + messages);
                assertTrue(messages.get(0).startsWith("メンバー FLP010.proc: "), messages.get(0));
            }
        }
    }
}
