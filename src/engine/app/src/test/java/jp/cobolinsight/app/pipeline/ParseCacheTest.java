package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.rules.RuleSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a scan keeps beside its project file comes back as a fresh parse gives it, until an input changes. */
class ParseCacheTest {

    private static final Path SAMPLES =
            Path.of("..", "..", "..", "samples", "seeded").toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    @Test
    void aResultComesBackAsAFreshParseGivesItUntilAnInputChanges() throws IOException {
        Path assets = tempDir.resolve("assets");
        for (String dir : List.of("bms", "cobol", "copybook", "jcl")) {
            copyDirectory(SAMPLES.resolve(dir), assets.resolve(dir));
        }
        Path databaseFile = tempDir.resolve("scan.db");
        Pipelines.scan(assets, databaseFile, List.of(assets.resolve("copybook")), Map.of());

        SourceSet fresh = walked(assets, null);
        new Parse().apply(fresh);
        SourceSet again = walked(assets, databaseFile);
        ParseCache cache = ParseCache.open(again);
        assertFalse(again.unitsOf(AssetKind.COBOL).isEmpty());
        for (SourceUnit unit : again.unitsOf(AssetKind.COBOL)) {
            assertEquals(fresh.programsByPath().get(unit.relPath()),
                    cache.cobol(unit).value().orElseThrow(), unit.relPath());
        }
        assertFalse(fresh.jobsByPath().isEmpty());
        for (SourceUnit unit : again.unitsOf(AssetKind.JCL)) {
            if (fresh.jobsByPath().containsKey(unit.relPath())) {
                assertEquals(fresh.jobsByPath().get(unit.relPath()),
                        cache.jcl(unit).value().orElseThrow(), unit.relPath());
            }
        }

        Files.writeString(assets.resolve("copybook").resolve("SYKCPY1.cpy"),
                System.lineSeparator() + "      *TOUCH", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        SourceSet changed = walked(assets, databaseFile);
        ParseCache after = ParseCache.open(changed);
        int copying = 0;
        for (SourceUnit unit : changed.unitsOf(AssetKind.COBOL)) {
            boolean copies = fresh.programsByPath().get(unit.relPath()).copyExpansions().stream()
                    .anyMatch(expansion -> expansion.copybookPath().endsWith("SYKCPY1.cpy"));
            copying += copies ? 1 : 0;
            assertEquals(copies, after.cobol(unit) == null,
                    () -> "a changed copybook parses again the programs that copy it: "
                            + unit.relPath());
        }
        assertTrue(copying > 0 && copying < changed.unitsOf(AssetKind.COBOL).size(),
                "some programs copy SYKCPY1 and some do not");
        for (SourceUnit unit : changed.unitsOf(AssetKind.JCL)) {
            if (fresh.jobsByPath().containsKey(unit.relPath())) {
                assertNotNull(after.jcl(unit), unit.relPath());
            }
        }
    }

    /**
     * Run from a jar, the engine is every jar beside it: the lib directory of installDist, the app
     * directory of the jpackage image, the directory of the jar {@code java -jar} names. The class
     * path, which {@code java -jar} reduces to the one jar, plays no part.
     */
    @Test
    void theFingerprintFollowsEveryJarBesideTheEnginesOwn() throws IOException {
        Path lib = Files.createDirectories(tempDir.resolve("lib"));
        Path app = Files.writeString(lib.resolve("app-2.2.0.jar"), "app");
        Path core = Files.writeString(lib.resolve("core-2.2.0.jar"), "core");
        Files.writeString(lib.resolve("notes.txt"), "notes");
        String before = ParseCache.engineFingerprint("2.2.0", app, app.toString());

        assertEquals(before, ParseCache.engineFingerprint("2.2.0", app, "elsewhere.jar"));
        Files.writeString(lib.resolve("notes.txt"), "other notes");
        assertEquals(before, ParseCache.engineFingerprint("2.2.0", app, app.toString()));
        assertNotEquals(before, ParseCache.engineFingerprint("2.2.1", app, app.toString()));

        Files.setLastModifiedTime(core, FileTime.fromMillis(
                Files.getLastModifiedTime(core).toMillis() - 60_000));
        String touched = ParseCache.engineFingerprint("2.2.0", app, app.toString());
        assertNotEquals(before, touched);
        Files.writeString(core, "core rebuilt");
        String rebuilt = ParseCache.engineFingerprint("2.2.0", app, app.toString());
        assertNotEquals(touched, rebuilt);
        Files.writeString(lib.resolve("added-2.2.0.jar"), "added");
        assertNotEquals(rebuilt, ParseCache.engineFingerprint("2.2.0", app, app.toString()));
    }

    /** Run from class directories, as in a build, the class path entries stand in, files and all. */
    @Test
    void fromClassDirectoriesTheFingerprintFollowsTheClassPath() throws IOException {
        Path classes = Files.createDirectories(tempDir.resolve("classes/jp"));
        Path type = Files.writeString(classes.resolve("Persist.class"), "v1");
        Path jars = Files.createDirectories(tempDir.resolve("jars"));
        Path dependency = Files.writeString(jars.resolve("dep.jar"), "dep");
        String classPath = String.join(File.pathSeparator, tempDir.resolve("classes").toString(),
                jars + File.separator + "*");
        String before = ParseCache.engineFingerprint(null, tempDir.resolve("classes"), classPath);

        Files.writeString(type, "v2");
        String recompiled = ParseCache.engineFingerprint(null, tempDir.resolve("classes"),
                classPath);
        assertNotEquals(before, recompiled);
        Files.writeString(dependency, "dep upgraded");
        assertNotEquals(recompiled, ParseCache.engineFingerprint(null, tempDir.resolve("classes"),
                classPath));
    }

    /** A cache that cannot be written beside the project file leaves the scan as it is. */
    @Test
    void aCacheThatCannotBeWrittenLeavesTheScanAsItIs() throws IOException {
        Path assets = tempDir.resolve("assets");
        copyDirectory(SAMPLES.resolve("cobol"), assets.resolve("cobol"));
        copyDirectory(SAMPLES.resolve("copybook"), assets.resolve("copybook"));
        Path databaseFile = tempDir.resolve("project").resolve("scan.db");
        Files.createDirectories(ParseCache.fileOf(databaseFile));
        assertEquals(databaseFile.resolveSibling("scan.db.parse-cache"),
                ParseCache.fileOf(databaseFile));

        for (int round = 0; round < 2; round++) {
            assertEquals(0, Pipelines.scan(assets, databaseFile,
                    List.of(assets.resolve("copybook")), Map.of()).summary().exitCode());
        }
        assertTrue(Files.isDirectory(ParseCache.fileOf(databaseFile)));
        try (Stream<Path> files = Files.list(databaseFile.getParent())) {
            assertEquals(List.of("scan.db", "scan.db.parse-cache"),
                    files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    private static final Path BATCH =
            Path.of("..", "..", "..", "samples", "field", "batch").toAbsolutePath().normalize();

    /** An unchanged rescan parses nothing; one changed program is the one asset parsed again. */
    @Test
    void anUnchangedRescanParsesNothingAndAChangedProgramParsesOnce() throws IOException {
        Path assets = tempDir.resolve("batch");
        copyDirectory(BATCH, assets);
        Path databaseFile = tempDir.resolve("batch.db");
        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());

        int before = Parse.FRESH_PARSES.get();
        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());
        assertEquals(0, Parse.FRESH_PARSES.get() - before);

        Files.writeString(assets.resolve("FLB030.cbl"), System.lineSeparator() + "      *TOUCH",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        before = Parse.FRESH_PARSES.get();
        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());
        assertEquals(1, Parse.FRESH_PARSES.get() - before);
    }

    /**
     * What the cache gives back equals a fresh parse for the assets that report: a program with
     * a syntax error, a JCL file with a diagnostic and a PROC member no job expands, broken too.
     */
    @Test
    void whatAnAssetReportedComesBackAsAFreshParseGivesIt() throws IOException {
        Path assets = tempDir.resolve("broken");
        copyDirectory(BATCH, assets);
        Files.writeString(assets.resolve("BAD.cbl"), String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. BAD.",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           MOVE TO.",
                "           STOP RUN.",
                ""));
        Files.writeString(assets.resolve("BADJOB.jcl"), String.join("\n",
                "//BADJOB   JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=FLB030",
                "//DD1      DD   DSN=A.B,DISP=SHR,QNAME=TPPROCESS",
                ""));
        Files.writeString(assets.resolve("BADPROC.proc"), String.join("\n",
                "//BADPROC  PROC",
                "//STEPP    EXEC PGM=FLB030",
                "//DD1      DD   DSN=A.B,DISP=SHR,QNAME=TPPROCESS",
                ""));
        Path databaseFile = tempDir.resolve("broken.db");
        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());

        SourceSet fresh = walked(assets, null, List.of(assets), Map.of(), List.of());
        new Parse().apply(fresh);
        SourceSet cached = walked(assets, databaseFile, List.of(assets), Map.of(), List.of());
        int before = Parse.FRESH_PARSES.get();
        new Parse().apply(cached);

        assertEquals(0, Parse.FRESH_PARSES.get() - before);
        assertTrue(fresh.findings().stream().anyMatch(f -> f.message().contains("DISP")),
                () -> "the JCL diagnostics are there to compare: " + fresh.findings());
        assertTrue(fresh.findingsOf("BAD.cbl").size() + fresh.findingsOf("BADPROC.proc").size()
                >= 2, () -> "the program and the member report: " + fresh.findings());
        assertEquals(fresh.findings(), cached.findings());
        assertEquals(fresh.programsByPath(), cached.programsByPath());
        assertEquals(fresh.jobsByPath(), cached.jobsByPath());
        assertEquals(fresh.unanalyzable(), cached.unanalyzable());
    }

    /** Each input of a program's parse, changed on its own, parses the program again. */
    @Test
    void eachInputOfAProgramChangedOnItsOwnParsesItAgain() throws IOException {
        Path assets = tempDir.resolve("cobol-inputs");
        copyDirectory(BATCH, assets);
        Path other = Files.createDirectories(tempDir.resolve("other-copybooks"));
        Path databaseFile = tempDir.resolve("cobol-inputs.db");
        List<Path> paths = List.of(assets, other);
        Pipelines.scan(assets, databaseFile, paths, Map.of());
        assertTrue(allCobolCached(walked(assets, databaseFile, paths, Map.of(), List.of())));

        assertTrue(noCobolCached(walked(assets, databaseFile, paths,
                Map.of("FLB020.cbl", "Shift_JIS"), List.of())), "a code page override");
        assertTrue(noCobolCached(walked(assets, databaseFile, List.of(assets), Map.of(),
                List.of())), "a search path removed");
        assertTrue(noCobolCached(walked(assets, databaseFile, List.of(other, assets), Map.of(),
                List.of())), "the search paths reordered");
        Files.writeString(other.resolve("NEWCPY.cpy"), "      *NEW\n");
        assertTrue(noCobolCached(walked(assets, databaseFile, paths, Map.of(), List.of())),
                "a copybook added where a COPY may find it");
        Files.delete(other.resolve("NEWCPY.cpy"));
        assertTrue(allCobolCached(walked(assets, databaseFile, paths, Map.of(), List.of())));

        Path cacheFile = ParseCache.fileOf(databaseFile);
        byte[] intact = Files.readAllBytes(cacheFile);
        rewriteHeader(cacheFile, "cobol-insight parse cache 0", null);
        assertTrue(noCobolCached(walked(assets, databaseFile, paths, Map.of(), List.of())),
                "another format line");
        Files.write(cacheFile, intact);
        rewriteHeader(cacheFile, null, "another engine");
        assertTrue(noCobolCached(walked(assets, databaseFile, paths, Map.of(), List.of())),
                "another engine line");
    }

    /** Each input of a job's parse, changed on its own, parses the jobs it concerns again. */
    @Test
    void eachInputOfAJobChangedOnItsOwnParsesItAgain() throws IOException {
        Path assets = tempDir.resolve("jcl-inputs");
        copyDirectory(BATCH, assets);
        Files.move(assets.resolve("FLP010.proc"), tempDir.resolve("FLP010.proc"));
        Path library = Files.createDirectories(tempDir.resolve("proclib"));
        Files.move(tempDir.resolve("FLP010.proc"), library.resolve("FLP010.proc"));
        Path databaseFile = tempDir.resolve("jcl-inputs.db");
        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of(),
                RuleSet.load((Path) null), List.of(library));
        assertEquals(Map.of("FLJ010.jcl", true, "FLJ020.jcl", true, "FLJ030.jcl", true),
                jclCached(assets, databaseFile, List.of(library)));

        Path member = library.resolve("FLP010.proc");
        String text = Files.readString(member, StandardCharsets.UTF_8);
        Files.writeString(member, text + "//*  TOUCH\n", StandardCharsets.UTF_8);
        assertEquals(Map.of("FLJ010.jcl", false, "FLJ020.jcl", false, "FLJ030.jcl", true),
                jclCached(assets, databaseFile, List.of(library)),
                "a member outside the asset folder edited");
        Files.writeString(member, text, StandardCharsets.UTF_8);

        Path elsewhere = Files.createDirectories(tempDir.resolve("proclib2"));
        assertEquals(Map.of("FLJ010.jcl", false, "FLJ020.jcl", false, "FLJ030.jcl", false),
                jclCached(assets, databaseFile, List.of(library, elsewhere)),
                "the --proc-path directories changed");

        Files.copy(BATCH.resolve("FLJ030.jcl"), assets.resolve("FLJ031.jcl"));
        assertEquals(false, jclCached(assets, databaseFile, List.of(library)).get("FLJ030.jcl"),
                "a JCL file added");
        Files.delete(assets.resolve("FLJ031.jcl"));
        Files.delete(assets.resolve("FLJ030.jcl"));
        assertEquals(false, jclCached(assets, databaseFile, List.of(library)).get("FLJ010.jcl"),
                "a JCL file removed");
    }

    /** A member inside the asset folder, edited, parses again the jobs that expand it. */
    @Test
    void aMemberInsideTheAssetFolderEditedParsesItsJobsAgain() throws IOException {
        Path assets = tempDir.resolve("member-inside");
        copyDirectory(BATCH, assets);
        Path databaseFile = tempDir.resolve("member-inside.db");
        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());

        Files.writeString(assets.resolve("FLP010.proc"), "//*  TOUCH\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertEquals(Map.of("FLJ010.jcl", false, "FLJ020.jcl", false, "FLJ030.jcl", true),
                jclCached(assets, databaseFile, List.of()));
    }

    /** Two asset folders scanned into one project file keep each other's results. */
    @Test
    void twoAssetFoldersKeepEachOthersResults() throws IOException {
        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");
        copyDirectory(BATCH, first);
        copyDirectory(BATCH, second);
        Path databaseFile = tempDir.resolve("shared.db");
        Pipelines.scan(first, databaseFile, List.of(first), Map.of());
        Pipelines.scan(second, databaseFile, List.of(second), Map.of());

        int before = Parse.FRESH_PARSES.get();
        Pipelines.scan(first, databaseFile, List.of(first), Map.of());
        Pipelines.scan(second, databaseFile, List.of(second), Map.of());
        assertEquals(0, Parse.FRESH_PARSES.get() - before);
    }

    /** The results of a folder the project file no longer holds rows of leave the cache. */
    @Test
    void aFolderWithoutRowsLeavesTheCache() throws IOException {
        Path first = tempDir.resolve("kept");
        Path second = tempDir.resolve("dropped");
        copyDirectory(BATCH, first);
        copyDirectory(BATCH, second);
        Path databaseFile = tempDir.resolve("pruned.db");
        Pipelines.scan(first, databaseFile, List.of(first), Map.of());
        Pipelines.scan(second, databaseFile, List.of(second), Map.of());
        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            dao.inTransaction(() -> dao.findSourcesByRoot(Paths.rootOf(second))
                    .forEach(source -> {
                        dao.deleteSourceCascade(source.id());
                        dao.deleteNode(source.id());
                    }));
        }
        Pipelines.scan(first, databaseFile, List.of(first), Map.of());

        int before = Parse.FRESH_PARSES.get();
        Pipelines.scan(second, databaseFile, List.of(second), Map.of());
        assertTrue(Parse.FRESH_PARSES.get() - before > 0, "the dropped folder parses afresh");
    }

    /** A damaged cache file reads as empty: counts it does not back size nothing. */
    @Test
    void aDamagedCacheFileReadsAsEmpty() throws IOException {
        Path assets = tempDir.resolve("damaged");
        copyDirectory(BATCH, assets);
        Path databaseFile = tempDir.resolve("damaged.db");
        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());
        Path cacheFile = ParseCache.fileOf(databaseFile);
        String format;
        String engine;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(cacheFile))) {
            format = in.readUTF();
            engine = in.readUTF();
        }
        String root = Paths.rootOf(assets);
        for (int damage = 0; damage < 2; damage++) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF(format);
                out.writeUTF(engine);
                out.writeInt(1);
                out.writeUTF(root);
                out.writeUTF("FLB010.cbl");
                out.writeUTF("key");
                if (damage == 0) {
                    out.writeInt(Integer.MAX_VALUE);
                } else {
                    out.writeInt(0);
                    out.writeUTF("");
                    out.writeInt(Integer.MAX_VALUE);
                }
            }
            Files.write(cacheFile, bytes.toByteArray());
            assertTrue(noCobolCached(walked(assets, databaseFile, List.of(assets), Map.of(),
                    List.of())));
        }
        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());
        byte[] intact = Files.readAllBytes(cacheFile);
        Files.write(cacheFile, java.util.Arrays.copyOf(intact, intact.length / 2));
        assertTrue(noCobolCached(walked(assets, databaseFile, List.of(assets), Map.of(),
                List.of())), "a truncated file gives nothing back");
        assertEquals(0, Pipelines.scan(assets, databaseFile, List.of(assets), Map.of())
                .summary().exitCode());
    }

    /**
     * The listing walks every directory under a search path, so a copybook added in a
     * subdirectory parses again the program whose {@code COPY 'SUB/INNER'} found nothing before.
     */
    @Test
    void aCopybookAddedInASubdirectoryOfASearchPathParsesTheProgramAgain() throws IOException {
        Path assets = tempDir.resolve("nested");
        Files.createDirectories(assets);
        Path copybooks = Files.createDirectories(tempDir.resolve("nested-copy"));
        Files.createDirectories(copybooks.resolve("SUB"));
        Files.writeString(assets.resolve("SUBPGM.cbl"), String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. SUBPGM.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       COPY 'SUB/INNER'.",
                "       PROCEDURE DIVISION.",
                "           STOP RUN.",
                ""));
        Path databaseFile = tempDir.resolve("nested.db");
        Pipelines.scan(assets, databaseFile, List.of(copybooks), Map.of());
        assertTrue(allCobolCached(walked(assets, databaseFile, List.of(copybooks), Map.of(),
                List.of())));

        Files.writeString(copybooks.resolve("SUB").resolve("INNER.cpy"),
                "       01  INNER-REC PIC X(10).\n");
        assertTrue(noCobolCached(walked(assets, databaseFile, List.of(copybooks), Map.of(),
                List.of())));
    }

    /** A file of a search path renamed parses every program again, whatever it copies. */
    @Test
    void aFileRenamedInASearchPathParsesTheProgramsAgain() throws IOException {
        Path assets = tempDir.resolve("renamed");
        copyDirectory(BATCH, assets);
        Path copybooks = Files.createDirectories(tempDir.resolve("renamed-copy"));
        Files.writeString(copybooks.resolve("UNUSED.cpy"), "       01  UNUSED PIC X.\n");
        List<Path> paths = List.of(assets, copybooks);
        Path databaseFile = tempDir.resolve("renamed.db");
        Pipelines.scan(assets, databaseFile, paths, Map.of());
        assertTrue(allCobolCached(walked(assets, databaseFile, paths, Map.of(), List.of())));

        Files.move(copybooks.resolve("UNUSED.cpy"), copybooks.resolve("RENAMED.cpy"));
        assertTrue(noCobolCached(walked(assets, databaseFile, paths, Map.of(), List.of())));
    }

    /**
     * Each entry's checksum covers its path, key and value: an altered byte of a value, and a
     * value with its checksum moved under another program's entry, both parse afresh.
     */
    @Test
    void anAlteredOrMovedValueParsesTheAssetAfresh() throws IOException {
        Path assets = tempDir.resolve("altered");
        copyDirectory(BATCH, assets);
        Path databaseFile = tempDir.resolve("altered.db");
        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());
        Path cacheFile = ParseCache.fileOf(databaseFile);
        byte[] intact = Files.readAllBytes(cacheFile);

        CacheFile altered = CacheFile.read(intact);
        CacheEntry first = altered.cobolEntries().get(0);
        first.value[first.value.length / 2] ^= 0x01;
        Files.write(cacheFile, altered.bytes());
        assertEquals(List.of(first.relPath), notCached(assets, databaseFile));

        CacheFile moved = CacheFile.read(intact);
        CacheEntry from = moved.cobolEntries().get(0);
        CacheEntry to = moved.cobolEntries().get(1);
        to.value = from.value;
        to.checksum = from.checksum;
        Files.write(cacheFile, moved.bytes());
        assertEquals(List.of(to.relPath), notCached(assets, databaseFile));
    }

    /**
     * A model nested deeper than a reader takes back is left out of the cache when it is
     * written, however deep: the save neither fails nor overflows, and the asset parses again.
     */
    @Test
    void aModelNestedTooDeepIsLeftOutOfTheCache() throws IOException {
        Path assets = tempDir.resolve("deep");
        copyDirectory(BATCH, assets);
        Path databaseFile = tempDir.resolve("deep.db");
        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());
        SourceSet s = walked(assets, databaseFile, List.of(assets), Map.of(), List.of());
        ParseCache cache = ParseCache.open(s);
        SourceUnit unit = s.unitsOf(AssetKind.COBOL).get(0);
        s.unitsOf(AssetKind.COBOL).forEach(cache::cobol);
        jp.cobolinsight.core.source.SourcePosition at =
                jp.cobolinsight.core.source.SourcePosition.fileStart(unit.absPath().toString());
        jp.cobolinsight.core.source.SourceRange range =
                new jp.cobolinsight.core.source.SourceRange(at, at);
        jp.cobolinsight.core.semantic.Statement statement =
                new jp.cobolinsight.core.semantic.SimpleStatement("DISPLAY", "DISPLAY 'X'", range);
        for (int depth = 0; depth < 200_000; depth++) {
            statement = new jp.cobolinsight.core.semantic.CompoundStatement(
                    jp.cobolinsight.core.semantic.ControlKind.BRANCH, "X = 1",
                    List.of(new jp.cobolinsight.core.semantic.StatementBlock("THEN",
                            List.of(statement))), range);
        }
        jp.cobolinsight.core.semantic.CobolSemanticModel deep =
                new jp.cobolinsight.core.semantic.CobolSemanticModel("DEEP",
                        unit.absPath().toString(), List.of(),
                        List.of(new jp.cobolinsight.core.semantic.Procedure("P",
                                jp.cobolinsight.core.semantic.ProcedureKind.PARAGRAPH,
                                java.util.Optional.empty(), List.of(statement), range)),
                        List.of(), List.of(), List.of(), List.of(), List.of());
        cache.putCobol(unit, jp.cobolinsight.core.spi.ParseOutcome.success(deep));
        cache.putSql(unit.relPath(), List.of());

        cache.save(List.of(Paths.rootOf(assets)));

        assertEquals(List.of(unit.relPath()), notCached(assets, databaseFile));
    }

    /** The COBOL programs of the folder the cache gives nothing back for, by relative path. */
    private static List<String> notCached(Path assets, Path databaseFile) {
        SourceSet s = walked(assets, databaseFile, List.of(assets), Map.of(), List.of());
        ParseCache cache = ParseCache.open(s);
        return s.unitsOf(AssetKind.COBOL).stream().filter(unit -> cache.cobol(unit) == null)
                .map(SourceUnit::relPath).toList();
    }

    /** One entry of a cache file, as the file holds it. */
    private static final class CacheEntry {
        String root;
        String relPath;
        String key;
        List<String> dependencies;
        String dependencyDigest;
        byte[] value;
        String checksum;
    }

    /** A cache file read entry by entry, to alter and write back. */
    private record CacheFile(String format, String engine, List<CacheEntry> entries) {

        static CacheFile read(byte[] file) throws IOException {
            try (DataInputStream in =
                    new DataInputStream(new java.io.ByteArrayInputStream(file))) {
                String format = in.readUTF();
                String engine = in.readUTF();
                int count = in.readInt();
                List<CacheEntry> entries = new java.util.ArrayList<>();
                for (int i = 0; i < count; i++) {
                    CacheEntry entry = new CacheEntry();
                    entry.root = in.readUTF();
                    entry.relPath = in.readUTF();
                    entry.key = in.readUTF();
                    int dependencies = in.readInt();
                    entry.dependencies = new java.util.ArrayList<>();
                    for (int d = 0; d < dependencies; d++) {
                        entry.dependencies.add(in.readUTF());
                    }
                    entry.dependencyDigest = in.readUTF();
                    entry.value = in.readNBytes(in.readInt());
                    entry.checksum = in.readUTF();
                    entries.add(entry);
                }
                return new CacheFile(format, engine, entries);
            }
        }

        List<CacheEntry> cobolEntries() {
            return entries.stream().filter(entry -> entry.relPath.endsWith(".cbl")).toList();
        }

        byte[] bytes() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF(format);
                out.writeUTF(engine);
                out.writeInt(entries.size());
                for (CacheEntry entry : entries) {
                    out.writeUTF(entry.root);
                    out.writeUTF(entry.relPath);
                    out.writeUTF(entry.key);
                    out.writeInt(entry.dependencies.size());
                    for (String dependency : entry.dependencies) {
                        out.writeUTF(dependency);
                    }
                    out.writeUTF(entry.dependencyDigest);
                    out.writeInt(entry.value.length);
                    out.write(entry.value);
                    out.writeUTF(entry.checksum);
                }
            }
            return bytes.toByteArray();
        }
    }

    /**
     * A COPY resolves a name as written, so a copybook named PAY.REC added to a search path
     * parses again the program whose {@code COPY 'PAY.REC'} found nothing before.
     */
    @Test
    void aDottedFileAddedToASearchPathParsesTheProgramAgain() throws IOException {
        Path assets = tempDir.resolve("dotted");
        Files.createDirectories(assets);
        Path copybooks = Files.createDirectories(tempDir.resolve("dotted-copy"));
        Files.writeString(assets.resolve("PAYPGM.cbl"), String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. PAYPGM.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       COPY 'PAY.REC'.",
                "       PROCEDURE DIVISION.",
                "           STOP RUN.",
                ""));
        Path databaseFile = tempDir.resolve("dotted.db");
        Pipelines.scan(assets, databaseFile, List.of(copybooks), Map.of());
        assertTrue(allCobolCached(walked(assets, databaseFile, List.of(copybooks), Map.of(),
                List.of())));

        Files.writeString(copybooks.resolve("PAY.REC"), "       01  PAY-REC PIC X(10).\n");
        assertTrue(noCobolCached(walked(assets, databaseFile, List.of(copybooks), Map.of(),
                List.of())));
    }

    /**
     * A cached value no scan could have written reads as no value, and the asset is parsed
     * afresh: values nested deeper than the codec reads, a result with neither model nor
     * failure, and a model whose list holds strings where procedures belong.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aHostileCachedValueParsesTheAssetAfresh() throws IOException {
        Path assets = tempDir.resolve("hostile");
        copyDirectory(BATCH, assets);
        Path databaseFile = tempDir.resolve("hostile.db");
        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());
        Path cacheFile = ParseCache.fileOf(databaseFile);
        byte[] intact = Files.readAllBytes(cacheFile);
        byte[] deep = new byte[2_000_000];
        java.util.Arrays.fill(deep, (byte) 0x09);
        jp.cobolinsight.core.semantic.CobolSemanticModel wrongList =
                new jp.cobolinsight.core.semantic.CobolSemanticModel("PGM", "PGM.cbl",
                        List.of(), (List) List.of("NOT A PROCEDURE"), List.of(), List.of(),
                        List.of(), List.of(), List.of());
        for (byte[] hostile : List.of(deep,
                RecordCodec.encode(new ParseCache.CobolResult(null, null, List.of())),
                RecordCodec.encode(new ParseCache.CobolResult(wrongList, null, List.of())))) {
            Files.write(cacheFile, withEveryValue(intact, hostile));
            assertTrue(noCobolCached(walked(assets, databaseFile, List.of(assets), Map.of(),
                    List.of())));
            assertEquals(0, Pipelines.scan(assets, databaseFile, List.of(assets), Map.of())
                    .summary().exitCode());
        }
    }

    /**
     * A cache file with the value of every entry replaced by {@code value}, each under the
     * checksum that value gives, so the value itself reaches the decoder.
     */
    private static byte[] withEveryValue(byte[] file, byte[] value) throws IOException {
        CacheFile cache = CacheFile.read(file);
        for (CacheEntry entry : cache.entries()) {
            entry.value = value;
            entry.checksum = ParseCache.checksum(entry.relPath, entry.key, entry.dependencies,
                    entry.dependencyDigest, value);
        }
        return cache.bytes();
    }

    /**
     * The cache file is written through a temporary file of a name the scan picks afresh: a file
     * planted at the name a fixed temporary file would take stays as it was, and once the scan
     * is done the folder holds the project file, its cache and that planted file alone.
     */
    @Test
    void theCacheIsWrittenThroughAFreshTemporaryFile() throws IOException {
        Path assets = tempDir.resolve("written");
        copyDirectory(BATCH, assets);
        Path databaseFile = tempDir.resolve("project").resolve("written.db");
        Files.createDirectories(databaseFile.getParent());
        Path planted = Files.writeString(
                databaseFile.resolveSibling("written.db.parse-cache.tmp"), "PLANTED");

        Pipelines.scan(assets, databaseFile, List.of(assets), Map.of());

        assertEquals("PLANTED", Files.readString(planted));
        try (Stream<Path> files = Files.list(databaseFile.getParent())) {
            assertEquals(List.of("written.db", "written.db.parse-cache",
                            "written.db.parse-cache.tmp"),
                    files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    private static boolean allCobolCached(SourceSet s) {
        ParseCache cache = ParseCache.open(s);
        return s.unitsOf(AssetKind.COBOL).stream().allMatch(unit -> cache.cobol(unit) != null);
    }

    private static boolean noCobolCached(SourceSet s) {
        ParseCache cache = ParseCache.open(s);
        return s.unitsOf(AssetKind.COBOL).stream().allMatch(unit -> cache.cobol(unit) == null);
    }

    /** Whether the cache gives back each job of the folder, by relative path. */
    private static Map<String, Boolean> jclCached(Path assets, Path databaseFile,
            List<Path> procedureLibraryPaths) {
        SourceSet s = walked(assets, databaseFile, List.of(assets), Map.of(),
                procedureLibraryPaths);
        ParseCache cache = ParseCache.open(s);
        Map<String, Boolean> cached = new java.util.TreeMap<>();
        for (SourceUnit unit : s.unitsOf(AssetKind.JCL)) {
            if (unit.relPath().startsWith("FLJ")) {
                cached.put(unit.relPath(), cache.jcl(unit) != null);
            }
        }
        return cached;
    }

    /** Rewrites the format line or the engine line of a cache file, leaving the rest. */
    private static void rewriteHeader(Path cacheFile, String format, String engine)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(cacheFile));
                DataOutputStream out = new DataOutputStream(bytes)) {
            String oldFormat = in.readUTF();
            String oldEngine = in.readUTF();
            out.writeUTF(format != null ? format : oldFormat);
            out.writeUTF(engine != null ? engine : oldEngine);
            out.write(in.readAllBytes());
        }
        Files.write(cacheFile, bytes.toByteArray());
    }

    /** A source set walked, classified and decoded the way scan does it, up to the parse. */
    private static SourceSet walked(Path assets, Path databaseFile) {
        return walked(assets, databaseFile, List.of(assets.resolve("copybook")), Map.of(),
                List.of());
    }

    private static SourceSet walked(Path assets, Path databaseFile, List<Path> copybookPaths,
            Map<String, String> codepages, List<Path> procedureLibraryPaths) {
        SourceSet s = new SourceSet(Pipelines.options(assets, databaseFile, copybookPaths,
                codepages, RuleSet.load((Path) null), EnumSet.of(Needs.SQL, Needs.CALL_GRAPH),
                procedureLibraryPaths));
        Pipeline.run(List.of(new Discover(), new Classify(EnumSet.allOf(AssetKind.class), false),
                new Decode(EnumSet.allOf(AssetKind.class))), s);
        return s;
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
