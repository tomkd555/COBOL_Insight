package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.sql.CursorSignals;
import jp.cobolinsight.core.sql.HostVariableBinding;
import jp.cobolinsight.core.sql.SqlColumnRef;
import jp.cobolinsight.core.sql.SqlDeclaredColumn;
import jp.cobolinsight.core.sql.SqlRoutineDefinition;
import jp.cobolinsight.core.sql.SqlSetPair;
import jp.cobolinsight.core.sql.SqlStatementModel;
import jp.cobolinsight.core.sql.SqlStructureSignals;
import jp.cobolinsight.core.sql.WheneverClause;
import jp.cobolinsight.rules.RuleSet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A committed picture of what the SQL frontend reads out of every asset folder that holds SQL. For
 * each source the pipeline built statements from — a COBOL program with at least one EXEC SQL block,
 * or an SQL script — they are written as JSON to {@code <source>.sql.json} beside it, and a run that
 * produces anything else fails naming the first line that differs.
 *
 * <p>The point is that a change to the frontend shows up as a diff somebody has to read, the way
 * the expected-findings tables do for the rules. Run with {@code -Dgolden.regenerate=true} to
 * rewrite the files after reading that diff.</p>
 *
 * <p>What the JSON holds is the model's own components under their own names, in their own order,
 * minus the two texts (originalText and mangledText, which would only repeat the fixture) and with
 * range written as the start and end line, plus the file when the statement was pulled in from a
 * copybook. An empty Optional and an empty collection are left out, so what a statement does not
 * carry takes no line.</p>
 *
 * <p>A script's snapshot is an object rather than an array: beside its statements it holds the
 * routines it defines, which is what the call graph reads a script for.
 *
 * <p>Three things are checked as well as the content: no {@code .sql.json} may survive the program
 * it belongs to, no program holding an EXEC SQL block may quietly stop producing statements — one
 * that legitimately does is named in {@link #WITHOUT_A_MODEL} with its reason — and no entry of
 * that map may outlive what it was written about, which is what
 * {@link JclModelSnapshotTest#staleExclusions} asks of the twin map on the JCL side.</p>
 */
class SqlModelSnapshotTest {

    private static final Path REPO = Path.of("..", "..", "..").toAbsolutePath().normalize();

    private static final boolean REGENERATE = Boolean.getBoolean("golden.regenerate");

    private static final String SUFFIX = ".sql.json";

    private static final Pattern EXEC_SQL = Pattern.compile("(?i)\\bEXEC\\s+SQL\\b");

    /** What the file field says when the statement's position names no file of the walk. */
    private static final String UNKNOWN_FILE = "?";

    /** The asset folders measured, each walked as its own source set. */
    private static final List<String> FOLDERS =
            List.of("corpus-constructs", "samples-field", "samples", "corpus", "corpus-public");

    /** A program that holds an EXEC SQL block and still produces no statement, and why. */
    private static final Map<String, String> WITHOUT_A_MODEL = Map.of(
            "corpus-constructs/sql/CSQ308.cbl",
            "Che4z stops on \"Variable WS-CLOB-FILE-NAME is not defined\", "
                    + "so the program reaches no SQL parse at all");

    /** Only there because {@link SourceSet.Options} takes a database path; no step here writes one. */
    private static final Path UNUSED_DATABASE =
            Path.of(System.getProperty("java.io.tmpdir"), "cobol-insight-sql-snapshot.db");

    @Test
    void everyProgramWithEmbeddedSqlMatchesItsCommittedSnapshot() throws IOException {
        int compared = 0;
        List<String> stale = new ArrayList<>();
        for (String folder : FOLDERS) {
            Path dir = REPO.resolve(folder);
            if (!Files.isDirectory(dir)) {
                System.out.println("SqlModelSnapshotTest: " + dir + " is not there yet, skipped");
                continue;
            }
            if (REGENERATE) {
                deleteSnapshots(dir);
            }
            SourceSet s = run(dir);
            Map<String, String> repoPathByFile = repoPathByFile(s);
            Map<String, List<SqlRoutineDefinition>> routinesByScript = new TreeMap<>();
            for (SqlRoutineDefinition routine : s.sqlRoutines()) {
                routinesByScript.computeIfAbsent(routine.definedIn(), key -> new ArrayList<>())
                        .add(routine);
            }
            Set<String> scripts = new TreeSet<>();
            s.unitsOf(AssetKind.SQL).forEach(unit -> scripts.add(unit.relPath()));
            Set<Path> produced = new TreeSet<>();
            for (Map.Entry<String, List<SqlStatementModel>> entry : s.sqlByPath().entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                Path snapshot = dir.resolve(entry.getKey() + SUFFIX);
                produced.add(snapshot);
                String source = relative(dir.resolve(entry.getKey()));
                String json = scripts.contains(entry.getKey())
                        ? toScriptJson(entry.getValue(),
                                routinesByScript.getOrDefault(entry.getKey(), List.of()),
                                source, repoPathByFile)
                        : toJson(entry.getValue(), source, repoPathByFile);
                if (REGENERATE) {
                    Files.writeString(snapshot, json, StandardCharsets.UTF_8);
                    continue;
                }
                compare(snapshot, json);
                compared++;
            }
            assertNoSnapshotOutlivesItsProgram(dir, produced);
            assertEverySqlProgramProducesAModel(folder, s);
            stale.addAll(JclModelSnapshotTest.staleExclusions(folder, "SQL-bearing source",
                    sqlSources(s), withStatements(s), WITHOUT_A_MODEL.keySet()));
        }
        for (String key : WITHOUT_A_MODEL.keySet()) {
            if (FOLDERS.stream().noneMatch(folder -> key.startsWith(folder + "/"))) {
                stale.add(key + " is named in WITHOUT_A_MODEL and stands in no folder measured here");
            }
        }
        assertEquals(List.of(), stale, "an entry of WITHOUT_A_MODEL has stopped saying anything "
                + "true: the reason was accepted for one named file, so read what became of that "
                + "file and delete the entry");
        if (!REGENERATE) {
            assertTrue(compared > 0, "no snapshot was compared; the fixtures are missing");
        }
    }

    /** The sources meant to produce statements: an SQL script, or a program holding an EXEC SQL. */
    private static Set<String> sqlSources(SourceSet s) {
        Set<String> paths = new LinkedHashSet<>();
        s.unitsOf(AssetKind.SQL).forEach(unit -> paths.add(unit.relPath()));
        for (SourceUnit unit : s.unitsOf(AssetKind.COBOL)) {
            DecodedSource decoded = s.decoded().get(unit.relPath());
            if (decoded != null && EXEC_SQL.matcher(decoded.text()).find()) {
                paths.add(unit.relPath());
            }
        }
        return paths;
    }

    /** Those of them the pipeline built at least one statement from. */
    private static Set<String> withStatements(SourceSet s) {
        Set<String> paths = new LinkedHashSet<>();
        s.sqlByPath().forEach((path, statements) -> {
            if (!statements.isEmpty()) {
                paths.add(path);
            }
        });
        return paths;
    }

    /** Fails naming the first line that differs, so the reader sees the change and not the file. */
    private static void compare(Path snapshot, String actual) throws IOException {
        String relative = relative(snapshot);
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

    /** A snapshot whose program no longer produces statements has to go, not linger. */
    private static void assertNoSnapshotOutlivesItsProgram(Path dir, Set<Path> produced)
            throws IOException {
        for (Path snapshot : snapshots(dir)) {
            assertTrue(produced.contains(snapshot), relative(snapshot)
                    + " belongs to no program of this run; delete it or find out what stopped"
                    + " producing statements");
        }
    }

    /** A program that holds an EXEC SQL block and produces nothing is a regression, not a gap. */
    private static void assertEverySqlProgramProducesAModel(String folder, SourceSet s) {
        List<SourceUnit> units = new ArrayList<>(s.unitsOf(AssetKind.COBOL));
        // An SQL script carries its statements without an EXEC SQL wrapper, so the scan below
        // finds none; a script that produced nothing at all is still worth catching.
        for (SourceUnit script : s.unitsOf(AssetKind.SQL)) {
            String key = folder + "/" + script.relPath();
            assertTrue(s.sqlByPath().containsKey(script.relPath())
                            || WITHOUT_A_MODEL.containsKey(key),
                    key + " is an SQL script that produced no statement; fix it, or list it in"
                            + " WITHOUT_A_MODEL with the reason");
        }
        for (SourceUnit unit : units) {
            DecodedSource decoded = s.decoded().get(unit.relPath());
            if (decoded == null || !EXEC_SQL.matcher(decoded.text()).find()
                    || s.sqlByPath().containsKey(unit.relPath())) {
                continue;
            }
            String key = folder + "/" + unit.relPath();
            assertTrue(WITHOUT_A_MODEL.containsKey(key), key
                    + " holds an EXEC SQL block and produced no statement; fix it, or list it in"
                    + " WITHOUT_A_MODEL with the reason");
        }
    }

    private static SourceSet run(Path dir) {
        Set<AssetKind> kinds = EnumSet.allOf(AssetKind.class);
        SourceSet s = new SourceSet(new SourceSet.Options(dir, UNUSED_DATABASE, copybookPaths(dir),
                Map.of(), RuleSet.load((Path) null), Set.of(Needs.SQL)));
        Pipeline.run(List.of(new Discover(), new Classify(kinds, false), new Decode(kinds),
                new Parse(), new Semantic()), s);
        return s;
    }

    /** Wherever the walk found copybooks, which is the rule the CLI applies with no --copybook-path. */
    private static List<Path> copybookPaths(Path dir) {
        Set<Path> parents = new LinkedHashSet<>();
        for (SourceDiscovery.DiscoveredFile file
                : SourceDiscovery.discover(dir).filesOf(Set.of(AssetKind.COPYBOOK))) {
            Path parent = file.absPath().getParent();
            if (parent != null) {
                parents.add(parent);
            }
        }
        return List.copyOf(parents);
    }

    /** Clears the folder's snapshots before a regeneration, so no stale file survives it. */
    private static void deleteSnapshots(Path dir) throws IOException {
        for (Path file : snapshots(dir)) {
            Files.delete(file);
        }
    }

    private static List<Path> snapshots(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).sorted().toList();
        }
    }

    /** Every unit of this walk by the absolute path a frontend would put in a position. */
    private static Map<String, String> repoPathByFile(SourceSet s) {
        Map<String, String> byFile = new HashMap<>();
        for (SourceUnit unit : s.units()) {
            byFile.put(Paths.normalisedKey(unit.absPath().toString()), relative(unit.absPath()));
        }
        return byFile;
    }

    /** The file a statement really stands in, or null when the position names none of this walk. */
    private static String ownerOf(Map<String, String> repoPathByFile, String file) {
        try {
            return repoPathByFile.get(Paths.normalisedKey(file));
        } catch (InvalidPathException e) {
            // Che4z hands back its own URI for the code it inserts itself, and that is no path.
            return null;
        }
    }

    private static String relative(Path path) {
        return REPO.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    // ---- serialisation ----

    private static String toJson(List<SqlStatementModel> statements, String program,
            Map<String, String> repoPathByFile) {
        Json json = new Json();
        json.array(statements, (out, s) -> statement(out, s, program, repoPathByFile));
        return json.finish();
    }

    /**
     * A script's snapshot holds the routines it defines beside its statements, because a routine is
     * what the call graph reads a script for: its name, the line it stands on, and what each
     * statement of its body does to which table. A program's snapshot stays the bare array of
     * statements it has always been.
     */
    private static String toScriptJson(List<SqlStatementModel> statements,
            List<SqlRoutineDefinition> routines, String script,
            Map<String, String> repoPathByFile) {
        Json json = new Json();
        json.open('{');
        json.entry("statements");
        json.array(statements, (out, s) -> statement(out, s, script, repoPathByFile));
        json.entry("routines");
        json.array(routines, SqlModelSnapshotTest::routine);
        json.close('}');
        return json.finish();
    }

    private static void routine(Json json, SqlRoutineDefinition routine) {
        json.open('{');
        json.field("name", routine.name());
        json.number("line", routine.line());
        json.list("body", routine.body(), SqlModelSnapshotTest::routineStatement);
        json.close('}');
    }

    /** One statement of a routine body: what it is, where it stands, and the tables it touches. */
    private static void routineStatement(Json json, SqlStatementModel statement) {
        json.open('{');
        json.field("kind", statement.kind().name());
        json.number("line", statement.range().start().line());
        json.map("tableAccess", statement.tableAccess());
        json.close('}');
    }

    private static void statement(Json json, SqlStatementModel s, String program,
            Map<String, String> repoPathByFile) {
        json.open('{');
        json.field("kind", s.kind().name());
        json.list("hostVariables", s.hostVariables(), SqlModelSnapshotTest::binding);
        json.strings("referencedTables", s.referencedTables());
        String owner = ownerOf(repoPathByFile, s.range().start().file());
        if (owner == null) {
            // Che4z hands back its own URI for code it inserts itself: the lines belong to no file
            // of this walk, and saying so beats letting them read as the program's own.
            json.field("file", UNKNOWN_FILE);
        } else if (!owner.equals(program)) {
            // The statement was pulled in from a copybook, so its lines are that file's lines.
            json.field("file", owner);
        }
        json.number("startLine", s.range().start().line());
        json.number("endLine", s.range().end().line());
        json.object("structureSignals", s.structureSignals(), SqlModelSnapshotTest::signals);
        json.field("analysis", s.analysis().name());
        json.optional("diagnostic", s.diagnostic());
        json.optional("cursorName", s.cursorName());
        json.optional("positionedCursor", s.positionedCursor());
        json.strings("intoTargets", s.intoTargets());
        json.map("tableAccess", s.tableAccess());
        json.strings("cteNames", s.cteNames());
        json.list("columnRefs", s.columnRefs(), SqlModelSnapshotTest::columnRef);
        json.strings("selectList", s.selectList());
        json.list("setPairs", s.setPairs(), SqlModelSnapshotTest::setPair);
        json.strings("insertColumns", s.insertColumns());
        json.strings("insertValues", s.insertValues());
        json.optional("declaredTable", s.declaredTable());
        json.list("declaredColumns", s.declaredColumns(), SqlModelSnapshotTest::declaredColumn);
        json.object("whenever", s.whenever().orElse(null), SqlModelSnapshotTest::whenever);
        json.optional("includeMember", s.includeMember());
        json.optional("procedureName", s.procedureName());
        json.optional("statementName", s.statementName());
        json.flag("withHold", s.withHold());
        json.flag("forUpdate", s.forUpdate());
        json.strings("forUpdateColumns", s.forUpdateColumns());
        json.flag("hasWhere", s.hasWhere());
        json.flag("hasOrderBy", s.hasOrderBy());
        json.flag("dynamic", s.dynamic());
        json.optional("isolation", s.isolation());
        s.rowsetSize().ifPresent(size -> json.number("rowsetSize", size));
        json.optional("rowsetHostVariable", s.rowsetHostVariable());
        json.close('}');
    }

    private static void binding(Json json, HostVariableBinding binding) {
        json.open('{');
        json.field("originalName", binding.originalName());
        json.field("mangledName", binding.mangledName());
        json.optional("indicatorName", binding.indicatorName());
        json.close('}');
    }

    private static void signals(Json json, SqlStructureSignals signals) {
        json.open('{');
        json.flag("selectStar", signals.selectStar());
        json.strings("nonSargablePredicates", signals.nonSargablePredicates());
        json.strings("functionOnColumnPredicates", signals.functionOnColumnPredicates());
        json.object("cursor", signals.cursor().orElse(null), SqlModelSnapshotTest::cursor);
        json.flag("hasFetchFirst", signals.hasFetchFirst());
        json.flag("hasOptimizeFor", signals.hasOptimizeFor());
        json.flag("hasWithUr", signals.hasWithUr());
        json.close('}');
    }

    private static void cursor(Json json, CursorSignals cursor) {
        json.open('{');
        json.field("cursorName", cursor.cursorName());
        json.flag("forReadOnly", cursor.forReadOnly());
        json.flag("forFetchOnly", cursor.forFetchOnly());
        json.flag("forUpdate", cursor.forUpdate());
        json.strings("forUpdateColumns", cursor.forUpdateColumns());
        json.close('}');
    }

    private static void columnRef(Json json, SqlColumnRef ref) {
        json.open('{');
        json.optional("table", ref.table());
        json.field("column", ref.column());
        json.close('}');
    }

    private static void setPair(Json json, SqlSetPair pair) {
        json.open('{');
        json.field("column", pair.column());
        json.field("value", pair.value());
        json.close('}');
    }

    private static void declaredColumn(Json json, SqlDeclaredColumn column) {
        json.open('{');
        json.field("name", column.name());
        json.field("type", column.type());
        json.flag("nullable", column.nullable());
        json.close('}');
    }

    private static void whenever(Json json, WheneverClause.Clause clause) {
        json.open('{');
        json.field("condition", clause.condition());
        json.optional("target", clause.target());
        json.close('}');
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
        private int depth;

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

        /** A boolean is written whether it is set or not: false is what a rule reads too. */
        void flag(String name, boolean value) {
            entry(name);
            out.append(value);
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
