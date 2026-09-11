package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.jcl.JclDataset;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclDisposition;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclMemberMiss;
import jp.cobolinsight.core.jcl.JclOverrideMiss;
import jp.cobolinsight.core.jcl.JclReferbackMiss;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.jcl.JclUtilityFacts;
import jp.cobolinsight.core.jcl.JclUtilityFacts.BindRequest;
import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetUse;
import jp.cobolinsight.core.jcl.JclUtilityFacts.ProgramRun;
import jp.cobolinsight.core.jcl.JclUtilityFacts.TableUse;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.frontend.jcl.MapaJclParser;
import jp.cobolinsight.rules.RuleSet;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A committed picture of what the JCL frontend reads out of every asset folder that holds JCL. For
 * each file the pipeline built a job model of, the jobs are written as JSON to
 * {@code <file>.jcl.json} beside the file, and a run that produces anything else fails naming the
 * first line that differs.
 *
 * <p>The point is that a change to the frontend shows up as a diff somebody has to read, the way
 * the expected-findings tables do for the rules. Run with {@code -Dgolden.regenerate=true} to
 * rewrite the files after reading that diff.</p>
 *
 * <p>What the JSON holds is the model's own components under their own names, in their own order.
 * An empty Optional, an empty collection and a boolean that is not set take no line, so what a
 * job does not carry says nothing. A position is written as its line alone while it stands in the
 * fixture itself, and with the file name beside it where it stands in a member the fixture
 * expanded; no path ever reaches the file, because these snapshots are committed and a machine's
 * own checkout directory must not be.</p>
 *
 * <p>The run is measured every way round. A snapshot nothing produced any more, a JCL file that is
 * a job and produced no model, and an entry of {@link #WITHOUT_A_MODEL} that has stopped saying
 * anything true, each fail the test — the first says a fixture stopped being read, the second that
 * one stopped parsing, the third that an accepted reason outlived what it was about — so none of
 * them can pass unnoticed.</p>
 */
class JclModelSnapshotTest {

    private static final Path REPO = Path.of("..", "..", "..").toAbsolutePath().normalize();

    private static final boolean REGENERATE = Boolean.getBoolean("golden.regenerate");

    private static final String SUFFIX = ".jcl.json";

    /** The asset folders measured, each walked as its own source set. */
    private static final List<String> FOLDERS = List.of("corpus-constructs/jcl", "samples",
            "samples-field/batch", "corpus", "corpus-public");

    /** Only there because {@link SourceSet.Options} takes a database path; no step here writes one. */
    private static final Path UNUSED_DATABASE =
            Path.of(System.getProperty("java.io.tmpdir"), "cobol-insight-jcl-snapshot.db");

    /**
     * The JCL files that are jobs and are meant to produce no model, against the reason why. A file
     * is put here only once somebody has read the reason and accepted it; an entry left behind after
     * the file starts parsing again fails the test just as its absence does.
     *
     * <p>A file is named the way the failure messages name it, the folder first:
     * {@code samples/jcl/X.jcl}.
     */
    private static final Map<String, String> WITHOUT_A_MODEL = Map.of();

    @Test
    void everyJclFileWithAJobMatchesItsCommittedSnapshot() throws IOException {
        int compared = 0;
        List<String> wrong = new ArrayList<>();
        for (String folder : FOLDERS) {
            Path dir = REPO.resolve(folder);
            if (!Files.isDirectory(dir)) {
                System.out.println("JclModelSnapshotTest: " + dir + " is not there yet, skipped");
                continue;
            }
            if (REGENERATE) {
                deleteSnapshots(dir);
            }
            SourceSet s = run(dir);
            Set<Path> written = new LinkedHashSet<>();
            for (Map.Entry<String, List<JclJobModel>> entry : s.jobsByPath().entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                Path snapshot = dir.resolve(entry.getKey() + SUFFIX);
                written.add(snapshot.normalize());
                String json = toJson(fileNameOf(entry.getKey()), entry.getValue());
                if (REGENERATE) {
                    Files.writeString(snapshot, json, StandardCharsets.UTF_8);
                    continue;
                }
                compare(snapshot, json);
                compared++;
            }
            wrong.addAll(withoutAModel(s, folder));
            wrong.addAll(staleExclusions(folder, "JCL file", jclFiles(s), withAModel(s),
                    WITHOUT_A_MODEL.keySet()));
            if (!REGENERATE) {
                wrong.addAll(orphans(dir, written));
            }
        }
        for (String key : WITHOUT_A_MODEL.keySet()) {
            if (FOLDERS.stream().noneMatch(folder -> key.startsWith(folder + "/"))) {
                wrong.add(key + " is named in WITHOUT_A_MODEL and stands in no folder measured here");
            }
        }
        assertEquals(List.of(), wrong,
                "a JCL file stopped producing a job model, or a snapshot has no job behind it any "
                        + "more; read why, then name the file in WITHOUT_A_MODEL with a reason or "
                        + "rerun with -Dgolden.regenerate=true");
        if (!REGENERATE) {
            assertTrue(compared > 0, "no snapshot was compared; the fixtures are missing");
        }
    }

    /**
     * The JCL files of a folder that hold a JOB statement and produced no model all the same.
     * {@link MapaJclParser#isMember} is what {@link Parse} itself asks, so a catalogued PROC or an
     * INCLUDE member is not counted: its steps belong to the jobs that call it.
     */
    private static List<String> withoutAModel(SourceSet s, String folder) {
        MapaJclParser parser = new MapaJclParser();
        List<String> missing = new ArrayList<>();
        for (SourceUnit unit : s.unitsOf(AssetKind.JCL)) {
            DecodedSource decoded = s.decoded().get(unit.relPath());
            if (s.jobsByPath().containsKey(unit.relPath()) || decoded == null
                    || parser.isMember(decoded)
                    || WITHOUT_A_MODEL.containsKey(folder + "/" + unit.relPath())) {
                continue;
            }
            missing.add(folder + "/" + unit.relPath() + " holds a JOB statement and no job model"
                    + s.unanalyzable().getOrDefault(unit.relPath(), "").transform(
                            why -> why.isEmpty() ? "" : " (" + why + ")"));
        }
        return missing;
    }

    /**
     * The entries of {@link #WITHOUT_A_MODEL} that have stopped saying anything true. A reason was
     * accepted for one named file, so an entry naming a file the folder no longer holds, and one
     * naming a file that produces a job model again, are both a reason nobody is reading any more.
     *
     * <p>{@link SqlModelSnapshotTest} keeps a map of the same shape and asks the same two
     * questions of it, so the check is shared and the caller says what its files are called.
     *
     * @param folder the folder, as the keys name it
     * @param what how the message names a file of it: "JCL file", "SQL-bearing source"
     * @param files every such file of it, by its path under the folder
     * @param withAModel those of them the pipeline built a model from
     * @param excluded the keys of the caller's WITHOUT_A_MODEL
     */
    static List<String> staleExclusions(String folder, String what, Set<String> files,
            Set<String> withAModel, Set<String> excluded) {
        List<String> stale = new ArrayList<>();
        for (String key : excluded) {
            if (!key.startsWith(folder + "/")) {
                continue;
            }
            String relPath = key.substring(folder.length() + 1);
            if (!files.contains(relPath)) {
                stale.add(key + " is named in WITHOUT_A_MODEL and is no " + what + " of " + folder);
            } else if (withAModel.contains(relPath)) {
                stale.add(key + " is named in WITHOUT_A_MODEL and is a " + what
                        + " with a model again");
            }
        }
        return stale;
    }

    /** An entry kept after its file started parsing again, and one naming nothing, both fail. */
    @Test
    void aStaleEntryInWithoutAModelFails() {
        Set<String> files = Set.of("jcl/READS.jcl", "jcl/DOES-NOT-PARSE.jcl");
        Set<String> withAModel = Set.of("jcl/READS.jcl");
        assertEquals(List.of(), staleExclusions("samples", "JCL file", files, withAModel,
                Set.of("samples/jcl/DOES-NOT-PARSE.jcl", "corpus/jcl/ELSEWHERE.jcl")));
        assertEquals(
                List.of("samples/jcl/GONE.jcl is named in WITHOUT_A_MODEL and is no JCL file of "
                                + "samples",
                        "samples/jcl/READS.jcl is named in WITHOUT_A_MODEL and is a JCL file with "
                                + "a model again"),
                staleExclusions("samples", "JCL file", files, withAModel,
                        new LinkedHashSet<>(List.of("samples/jcl/GONE.jcl",
                                "samples/jcl/READS.jcl"))));
    }

    /** Every JCL file of a source set, by its path under the folder. */
    private static Set<String> jclFiles(SourceSet s) {
        Set<String> paths = new LinkedHashSet<>();
        s.unitsOf(AssetKind.JCL).forEach(unit -> paths.add(unit.relPath()));
        return paths;
    }

    /** The files of a source set the pipeline built at least one job model from. */
    private static Set<String> withAModel(SourceSet s) {
        Set<String> paths = new LinkedHashSet<>();
        s.jobsByPath().forEach((path, jobs) -> {
            if (!jobs.isEmpty()) {
                paths.add(path);
            }
        });
        return paths;
    }

    /** The snapshots of a folder no job of this run wrote: a fixture that stopped being read. */
    private static List<String> orphans(Path dir, Set<Path> written) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .map(Path::normalize)
                    .filter(p -> !written.contains(p))
                    .map(p -> REPO.relativize(p).toString().replace('\\', '/')
                            + " has no job model behind it any more")
                    .toList();
        }
    }

    /** Fails naming the first line that differs, so the reader sees the change and not the file. */
    private static void compare(Path snapshot, String actual) throws IOException {
        String relative = REPO.relativize(snapshot).toString().replace('\\', '/');
        if (!Files.exists(snapshot)) {
            fail(relative + " is missing; run with -Dgolden.regenerate=true after reading why");
        }
        String expected = Files.readString(snapshot, StandardCharsets.UTF_8);
        if (expected.equals(actual)) {
            return;
        }
        List<String> expectedLines = expected.lines().toList();
        List<String> actualLines = actual.lines().toList();
        for (int i = 0; i < Math.max(expectedLines.size(), actualLines.size()); i++) {
            String was = i < expectedLines.size() ? expectedLines.get(i) : "(no line)";
            String now = i < actualLines.size() ? actualLines.get(i) : "(no line)";
            if (!was.equals(now)) {
                fail(relative + " line " + (i + 1) + ": committed [" + was + "], now [" + now
                        + "]. Read the change, then rerun with -Dgolden.regenerate=true.");
            }
        }
        fail(relative + " differs only in its trailing newline");
    }

    /** Discover, classify, decode and parse, which is the front half of {@code scan}. */
    private static SourceSet run(Path dir) {
        Set<AssetKind> kinds = EnumSet.allOf(AssetKind.class);
        SourceSet s = new SourceSet(new SourceSet.Options(dir, UNUSED_DATABASE, List.of(),
                Map.of(), RuleSet.load((Path) null), Set.of()));
        Pipeline.run(List.of(new Discover(), new Classify(kinds, false), new Decode(kinds),
                new Parse()), s);
        return s;
    }

    /** Clears the folder's snapshots before a regeneration, so no stale file survives it. */
    private static void deleteSnapshots(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).toList()) {
                Files.delete(file);
            }
        }
    }

    // ---- serialisation ----

    private static String toJson(String fixture, List<JclJobModel> jobs) {
        Json json = new Json(fixture);
        json.array(jobs, JclModelSnapshotTest::job);
        return json.finish();
    }

    private static void job(Json json, JclJobModel job) {
        json.open('{');
        json.field("jobName", job.jobName());
        json.number("line", job.position().line());
        json.file(job.position());
        json.optional("condition", job.condition());
        json.map("parameters", job.parameters());
        json.strings("jcllib", job.jcllib());
        json.list("joblib", job.joblib(), JclModelSnapshotTest::dd);
        json.object("syschk", job.syschk().orElse(null), JclModelSnapshotTest::dd);
        json.strings("jes2Cards", job.jes2Cards());
        json.objects("outputStatements", job.outputStatements());
        json.strings("members", job.members().stream().map(JclModelSnapshotTest::fileName)
                .toList());
        json.strings("schedulerVariables", job.schedulerVariables());
        json.strings("unresolvedSymbols", job.unresolvedSymbols());
        json.list("unresolvedOverrides", job.unresolvedOverrides(),
                JclModelSnapshotTest::overrideMiss);
        json.list("unresolvedReferbacks", job.unresolvedReferbacks(),
                JclModelSnapshotTest::referbackMiss);
        json.list("missingMembers", job.missingMembers(), JclModelSnapshotTest::memberMiss);
        json.list("diagnostics", job.diagnostics(), JclModelSnapshotTest::diagnostic);
        json.list("steps", job.steps(), JclModelSnapshotTest::step);
        json.close('}');
    }

    private static void step(Json json, JclStep step) {
        json.open('{');
        json.field("name", step.name());
        json.field("execKind", step.execKind().name());
        json.field("target", step.target());
        json.number("line", step.position().line());
        json.file(step.position());
        json.optional("condition", step.condition());
        json.optional("procStepName", step.procStepName());
        json.optional("parm", step.parm());
        json.map("parameters", step.parameters());
        json.object("utility", step.utility().orElse(null), JclModelSnapshotTest::utility);
        json.list("ddStatements", step.ddStatements(), JclModelSnapshotTest::dd);
        json.close('}');
    }

    private static void dd(Json json, JclDdStatement dd) {
        json.open('{');
        json.field("ddName", dd.ddName());
        json.number("line", dd.position().line());
        json.file(dd.position());
        if (dd.concatIndex() > 0) {
            json.number("concatIndex", dd.concatIndex());
        }
        json.optional("datasetName", dd.datasetName());
        json.object("dataset", dd.dataset().orElse(null), JclModelSnapshotTest::dataset);
        json.optional("dispositionText", dd.dispositionText());
        json.object("disposition", dd.disposition().orElse(null),
                JclModelSnapshotTest::disposition);
        json.optional("sysout", dd.sysout());
        json.flag("dummy", dd.dummy());
        json.map("parameters", dd.parameters());
        json.map("referbacks", dd.referbacks());
        json.strings("inStreamData", dd.inStreamData());
        json.close('}');
    }

    private static void dataset(Json json, JclDataset dataset) {
        json.open('{');
        json.field("name", dataset.name());
        json.optional("member", dataset.member());
        dataset.gdgRelative().ifPresent(generation -> json.number("gdgRelative", generation));
        json.flag("temporary", dataset.temporary());
        json.optional("referback", dataset.referback());
        json.close('}');
    }

    private static void disposition(Json json, JclDisposition disposition) {
        json.open('{');
        json.field("status", disposition.status());
        json.optional("normal", disposition.normal());
        json.optional("abnormal", disposition.abnormal());
        json.field("raw", disposition.raw());
        json.close('}');
    }

    private static void utility(Json json, JclUtilityFacts facts) {
        json.open('{');
        json.list("programRuns", facts.programRuns(), JclModelSnapshotTest::programRun);
        json.list("binds", facts.binds(), JclModelSnapshotTest::bind);
        json.list("datasetUses", facts.datasetUses(), JclModelSnapshotTest::datasetUse);
        json.list("tableUses", facts.tableUses(), JclModelSnapshotTest::tableUse);
        json.enums("ddRoles", facts.ddRoles());
        json.close('}');
    }

    private static void programRun(Json json, ProgramRun run) {
        json.open('{');
        json.field("program", run.program());
        json.optional("plan", run.plan());
        json.optional("parms", run.parms());
        json.optional("library", run.library());
        json.close('}');
    }

    private static void bind(Json json, BindRequest bind) {
        json.open('{');
        json.field("kind", bind.kind());
        json.field("name", bind.name());
        json.strings("members", bind.members());
        json.map("options", bind.options());
        json.close('}');
    }

    private static void datasetUse(Json json, DatasetUse use) {
        json.open('{');
        json.field("dataset", use.dataset());
        json.field("access", use.access().name());
        json.close('}');
    }

    private static void tableUse(Json json, TableUse use) {
        json.open('{');
        json.field("table", use.table());
        json.field("access", use.access().name());
        json.close('}');
    }

    private static void overrideMiss(Json json, JclOverrideMiss miss) {
        json.open('{');
        json.number("line", miss.line());
        json.field("text", miss.text());
        json.close('}');
    }

    private static void referbackMiss(Json json, JclReferbackMiss miss) {
        json.open('{');
        json.number("line", miss.line());
        json.field("text", miss.text());
        json.close('}');
    }

    private static void memberMiss(Json json, JclMemberMiss miss) {
        json.open('{');
        json.number("line", miss.line());
        json.field("kind", miss.kind());
        json.field("name", miss.name());
        json.close('}');
    }

    private static void diagnostic(Json json, Finding finding) {
        json.open('{');
        json.field("ruleId", finding.ruleId());
        json.number("line", finding.location().line());
        json.file(finding.location());
        json.field("message", withoutRepoPath(finding.message()));
        json.close('}');
    }

    /** A member as the model names it, cut to the file name: the path is this machine's own. */
    private static String fileName(String path) {
        return Path.of(path).getFileName().toString();
    }

    /** The file name of a path, empty when there is nothing to take one from. */
    private static String fileNameOf(String path) {
        try {
            Path name = Path.of(path).getFileName();
            return name == null ? "" : name.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String withoutRepoPath(String message) {
        return message.replace(REPO + File.separator, "").replace(REPO.toString(), "");
    }

    /**
     * A JSON writer of exactly what these snapshots need: one value per line, two-space indent and
     * the fields in the order they are written, so a diff points at a field and not at a file.
     */
    private static final class Json {

        private interface Writer<T> {
            void write(Json json, T value);
        }

        private final StringBuilder out = new StringBuilder();
        private final List<Boolean> written = new ArrayList<>();
        /** The fixture being written, against which a position's own file is measured. */
        private final String fixture;
        private int depth;

        Json(String fixture) {
            this.fixture = fixture;
        }

        /**
         * The file a position stands in, written only where that is not the fixture itself: a step
         * or a DD of an expanded member keeps the line of the member it was written in, and the
         * line on its own would send a reader to the wrong file.
         */
        void file(SourcePosition position) {
            String name = fileNameOf(position.file());
            if (!name.equals(fixture)) {
                field("file", name.isEmpty() ? "?" : name);
            }
        }

        <T> void array(List<T> values, Writer<T> writer) {
            open('[');
            for (T value : values) {
                separate();
                indent();
                writer.write(this, value);
            }
            close(']');
        }

        void field(String name, String value) {
            entry(name);
            string(value);
        }

        void number(String name, int value) {
            entry(name);
            out.append(value);
        }

        /** A boolean takes a line only when it is set; what is not set says nothing. */
        void flag(String name, boolean value) {
            if (value) {
                entry(name);
                out.append(true);
            }
        }

        void optional(String name, Optional<String> value) {
            value.ifPresent(text -> field(name, text));
        }

        void strings(String name, List<String> values) {
            if (values.isEmpty()) {
                return;
            }
            entry(name);
            open('[');
            for (String value : values) {
                separate();
                indent();
                string(value);
            }
            close(']');
        }

        void map(String name, Map<String, String> values) {
            if (values.isEmpty()) {
                return;
            }
            entry(name);
            open('{');
            values.forEach(this::field);
            close('}');
        }

        <E extends Enum<E>> void enums(String name, Map<String, E> values) {
            if (values.isEmpty()) {
                return;
            }
            entry(name);
            open('{');
            values.forEach((key, value) -> field(key, value.name()));
            close('}');
        }

        void objects(String name, Map<String, Map<String, String>> values) {
            if (values.isEmpty()) {
                return;
            }
            entry(name);
            open('{');
            values.forEach((key, value) -> {
                entry(key);
                open('{');
                value.forEach(this::field);
                close('}');
            });
            close('}');
        }

        <T> void list(String name, List<T> values, Writer<T> writer) {
            if (values.isEmpty()) {
                return;
            }
            entry(name);
            array(values, writer);
        }

        <T> void object(String name, T value, Writer<T> writer) {
            if (value == null) {
                return;
            }
            entry(name);
            writer.write(this, value);
        }

        void open(char bracket) {
            out.append(bracket);
            written.add(Boolean.FALSE);
            depth++;
        }

        void close(char bracket) {
            depth--;
            if (Boolean.TRUE.equals(written.remove(written.size() - 1))) {
                out.append('\n');
                indent();
            }
            out.append(bracket);
        }

        String finish() {
            return out.append('\n').toString();
        }

        private void entry(String name) {
            separate();
            indent();
            string(name);
            out.append(": ");
        }

        /** Puts the comma after the previous entry of this level, and the newline before this one. */
        private void separate() {
            if (Boolean.TRUE.equals(written.get(written.size() - 1))) {
                out.append(',');
            }
            written.set(written.size() - 1, Boolean.TRUE);
            out.append('\n');
        }

        private void indent() {
            out.append("  ".repeat(depth));
        }

        private void string(String value) {
            out.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }
    }
}
