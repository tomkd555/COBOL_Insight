package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.CopyExpansionEntry;
import jp.cobolinsight.core.source.CopyInlineExpansion;
import jp.cobolinsight.core.source.ExpandedCopyLine;
import jp.cobolinsight.core.spi.CobolParser;
import jp.cobolinsight.core.spi.JclParser;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.core.sql.SqlRoutineDefinition;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * What parsing each asset gave on the last scan into a project file, kept in a file beside it so
 * that the next scan takes it back for every asset whose inputs are unchanged, in place of parsing
 * the asset again. {@link Parse} and {@link Semantic} read it and add what they computed afresh;
 * {@link Persist} writes it once the project file is written.
 *
 * <p>A result is taken back only while every input it was computed from is the same, compared by
 * content:
 *
 * <ul>
 *   <li>every asset: its path, its bytes and the code page it is read in;</li>
 *   <li>a COBOL program: also the copybook search paths and the path, relative to its search
 *       path, of every regular file under each of them, subdirectories included. A COPY resolves
 *       a name as written ({@code COPY 'PAY.REC'} finds the file PAY.REC, {@code COPY 'SUB/X'}
 *       the file X of the subdirectory SUB) as well as with a copybook extension, so a file
 *       added, removed or renamed anywhere under a search path parses every program again. Also
 *       the content of every copybook the program brought in, so an edited copybook parses again
 *       the programs that copy it. A program whose parse failed depends on the content of every
 *       file under the search paths, since it names none it brought in. Its model and the
 *       outcome of each of its EXEC SQL blocks are kept together;</li>
 *   <li>a JCL job: also the content of every PROC and INCLUDE member it expanded, and the names of
 *       every JCL of the walk and every file of the {@code --proc-path} directories, which decide
 *       where a member is found;</li>
 *   <li>everything: the engine itself, read off its version and the size and time of every jar
 *       it was installed with, so a rebuilt engine starts from an empty cache.</li>
 * </ul>
 *
 * <p>A failed parse is kept as the finding it failed with. A BMS map is parsed on every scan. The
 * file is a cache: one that is missing, unreadable, damaged or written by another engine is read as
 * empty, and a failure to write it leaves the scan's own result untouched. Each entry carries the
 * SHA-256 of its path, key, dependencies and value; an entry whose bytes no longer give it, a value
 * altered or moved under another asset's key, is parsed afresh.
 *
 * <p>The cache stands beside the project file under the application's own data folder and is
 * trusted as that file is: whoever can write one can write the other, and the project file already
 * holds the results the cache would reproduce.
 */
final class ParseCache {

    private static final String FORMAT = "cobol-insight parse cache 2";

    /** The file beside the project file that holds the cache. */
    static Path fileOf(Path databaseFile) {
        return databaseFile.resolveSibling(databaseFile.getFileName() + ".parse-cache");
    }

    /**
     * What one COBOL program gave: its model and the outcome of each EXEC SQL block of the model in
     * the order {@link Semantic} reads them, or the finding its parse failed with. {@code sql} is
     * null until Semantic has read the blocks.
     */
    record CobolResult(CobolSemanticModel model, Finding failure, List<SqlResult> sql) {
    }

    /** One EXEC SQL block: the statement the SQL frontend read, or the finding it refused it with. */
    record SqlResult(SqlStatementModel statement, Finding failure) {
    }

    /** The jobs one JCL file holds, or the finding its parse failed with. */
    record JclResult(List<JclJobModel> jobs, Finding failure) {
    }

    /** What reading a PROC or INCLUDE member no job expanded found in it. */
    record MemberResult(List<Finding> diagnostics) {
    }

    /** What one SQL script gave: its findings in order, its statements and its routines. */
    record ScriptResult(List<Finding> findings, List<SqlStatementModel> statements,
            List<SqlRoutineDefinition> routines) {
    }

    /**
     * One result and what it was computed from: the key of the asset and its environment, the
     * files it read besides, and the digest of their content then. {@code value} is the result
     * encoded, or null for a result of this run not encoded yet; {@code checksum} is what
     * {@link #checksumOf} gave the entry when it was written, or null with the value.
     */
    private static final class Entry {
        final String relPath;
        final String key;
        final List<String> dependencies;
        final String dependencyDigest;
        byte[] value;
        Object decoded;
        String checksum;

        Entry(String relPath, String key, List<String> dependencies, String dependencyDigest,
                byte[] value, Object decoded) {
            this.relPath = relPath;
            this.key = key;
            this.dependencies = dependencies;
            this.dependencyDigest = dependencyDigest;
            this.value = value;
            this.decoded = decoded;
        }
    }

    private static String checksumOf(Entry entry) {
        return checksum(entry.relPath, entry.key, entry.dependencies, entry.dependencyDigest,
                entry.value);
    }

    /** The SHA-256 of an entry's path, key, dependencies, their digest and its value. */
    static String checksum(String relPath, String key, List<String> dependencies,
            String dependencyDigest, byte[] value) {
        StringBuilder text = new StringBuilder().append(relPath).append('\n').append(key)
                .append('\n');
        dependencies.forEach(dependency -> text.append(dependency).append('\n'));
        text.append(dependencyDigest).append('\n');
        byte[] head = text.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + value.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(value, 0, all, head.length, value.length);
        return Paths.sha256(all);
    }

    private final SourceSet set;
    private final Path file;
    private final String root;
    private final String engine;
    /** The entries of the last scan of this folder, by relative path. */
    private final Map<String, Entry> stored = new HashMap<>();
    /** The entries of other folders scanned into the same project file, kept as they are. */
    private final Map<String, List<Entry>> otherRoots = new TreeMap<>();
    /** The entries this scan keeps, by relative path. */
    private final Map<String, Entry> kept = new TreeMap<>();
    private final Map<String, SourceUnit> unitByPath = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /**
     * The digests of what every program and every JCL file of this run is read against, memoised:
     * they hold for one run of one {@link SourceSet}, whose search paths, options and walk stay
     * as they are for the run.
     */
    private String cobolEnvironment;
    private String jclEnvironment;
    private List<String> allCopybooks;
    private final Map<String, String> contentByPath = new HashMap<>();

    private ParseCache(SourceSet set, Path file) {
        this.set = set;
        this.file = file;
        this.root = Paths.rootOf(set.root());
        this.engine = engineFingerprint();
        for (SourceUnit unit : set.units()) {
            unitByPath.put(Paths.normalisedKey(unit.absPath().toString()), unit);
        }
    }

    /** The cache of the project file this run writes, or null for a run that writes none. */
    static ParseCache open(SourceSet s) {
        if (s.options().databaseFile() == null) {
            return null;
        }
        ParseCache cache = new ParseCache(s, fileOf(s.options().databaseFile()));
        cache.load();
        return cache;
    }

    // ---- what Parse and Semantic ask for and add ----

    /** The parse of a program, as {@link CobolParser} would give it, or null to parse it afresh. */
    ParseOutcome<CobolSemanticModel> cobol(SourceUnit unit) {
        CobolResult result = lookUp(unit, cobolKey(unit), CobolResult.class);
        if (result == null || result.sql() == null) {
            return null;
        }
        return result.failure() != null ? ParseOutcome.failure(result.failure())
                : ParseOutcome.success(result.model());
    }

    /**
     * Keeps the parse of a program, with the copybooks it depends on: those its model brought in,
     * nested ones included, or for a failed parse every copybook of the search paths.
     */
    void putCobol(SourceUnit unit, ParseOutcome<CobolSemanticModel> outcome) {
        if (outcome instanceof ParseOutcome.Failure<CobolSemanticModel> failure) {
            keep(unit, cobolKey(unit), allCopybooks(),
                    new CobolResult(null, failure.finding(), List.of()));
            return;
        }
        CobolSemanticModel model = outcome.value().orElseThrow();
        keep(unit, cobolKey(unit), copybooksOf(model), new CobolResult(model, null, null));
    }

    /** The files of the copybooks a program brought in, nested ones included, in name order. */
    private static List<String> copybooksOf(CobolSemanticModel model) {
        TreeSet<String> paths = new TreeSet<>();
        for (CopyExpansionEntry expansion : model.copyExpansions()) {
            paths.add(expansion.copybookPath());
        }
        for (CopyInlineExpansion expansion : model.copyInlineExpansions()) {
            paths.add(expansion.copybookPath());
            for (ExpandedCopyLine line : expansion.lines()) {
                line.copybookPath().ifPresent(paths::add);
            }
        }
        return List.copyOf(paths);
    }

    /** Every file under the copybook search paths a COPY can resolve to. */
    private List<String> allCopybooks() {
        if (allCopybooks == null) {
            TreeSet<String> paths = new TreeSet<>();
            for (Path directory : set.copybookSearchPaths()) {
                TreeSet<String> names = names(directory);
                for (String name : names == null ? List.<String>of() : names) {
                    paths.add(directory.toAbsolutePath().normalize().resolve(name).toString());
                }
            }
            allCopybooks = List.copyOf(paths);
        }
        return allCopybooks;
    }

    /** The SQL outcomes kept with a program's model, or null when they are still to be read. */
    List<SqlResult> sql(String relPath) {
        Entry entry = kept.get(relPath);
        return entry == null ? null : ((CobolResult) entry.decoded).sql();
    }

    void putSql(String relPath, List<SqlResult> results) {
        Entry entry = kept.get(relPath);
        if (entry != null) {
            entry.decoded = new CobolResult(((CobolResult) entry.decoded).model(), null,
                    List.copyOf(results));
            entry.value = null;
        }
    }

    /** The parse of a JCL file, as {@link JclParser} would give it, or null to parse it afresh. */
    ParseOutcome<List<JclJobModel>> jcl(SourceUnit unit) {
        JclResult result = lookUp(unit, jclKey(unit), JclResult.class);
        if (result == null) {
            return null;
        }
        return result.failure() != null ? ParseOutcome.failure(result.failure())
                : ParseOutcome.success(result.jobs());
    }

    /**
     * Keeps the parse of a JCL file, with the members its jobs expanded. A file fails before any
     * member is read, on its own text, so a failure depends on nothing besides.
     */
    void putJcl(SourceUnit unit, ParseOutcome<List<JclJobModel>> outcome) {
        if (outcome instanceof ParseOutcome.Failure<List<JclJobModel>> failure) {
            keep(unit, jclKey(unit), List.of(), new JclResult(null, failure.finding()));
            return;
        }
        List<JclJobModel> jobs = outcome.value().orElseThrow();
        TreeSet<String> members = new TreeSet<>();
        jobs.forEach(job -> members.addAll(job.members()));
        keep(unit, jclKey(unit), List.copyOf(members), new JclResult(jobs, null));
    }

    /** The diagnostics of a member read on its own, which depend on its own text alone. */
    List<Finding> memberDiagnostics(SourceUnit unit) {
        MemberResult result = lookUp(unit, ownKey(unit), MemberResult.class);
        return result == null ? null : result.diagnostics();
    }

    void putMemberDiagnostics(SourceUnit unit, List<Finding> diagnostics) {
        keep(unit, ownKey(unit), List.of(), new MemberResult(List.copyOf(diagnostics)));
    }

    ScriptResult script(SourceUnit unit) {
        return lookUp(unit, ownKey(unit), ScriptResult.class);
    }

    void putScript(SourceUnit unit, ScriptResult result) {
        keep(unit, ownKey(unit), List.of(), result);
    }

    /**
     * The stored result of an asset, when it was computed from the inputs {@code key} and the
     * files it read besides still hold, and it is the kind of result asked for; null otherwise.
     */
    private <T> T lookUp(SourceUnit unit, String key, Class<T> type) {
        Entry entry = stored.get(unit.relPath());
        if (entry == null || !entry.key.equals(key)
                || !entry.dependencyDigest.equals(digestOf(entry.dependencies))
                || !checksumOf(entry).equals(entry.checksum)) {
            return null;
        }
        Object decoded;
        try {
            decoded = RecordCodec.decode(entry.value);
            if (!type.isInstance(decoded) || !holds(decoded)) {
                return null;
            }
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            // Whatever the bytes make go wrong, the asset is parsed afresh: the cache is a cache.
            return null;
        }
        entry.decoded = decoded;
        kept.put(unit.relPath(), entry);
        return type.cast(decoded);
    }

    /**
     * Whether a result read back is one a scan could have kept: a parse gives a model or the
     * finding it failed with, never both and never neither, and so does each EXEC SQL block.
     * The element types of the lists RecordCodec has checked already.
     */
    private static boolean holds(Object result) {
        return switch (result) {
            case CobolResult cobol -> (cobol.model() == null) != (cobol.failure() == null)
                    && (cobol.sql() == null || cobol.sql().stream().allMatch(sql -> sql != null
                            && (sql.statement() == null) != (sql.failure() == null)));
            case JclResult jcl -> (jcl.jobs() == null) != (jcl.failure() == null);
            case MemberResult member -> member.diagnostics() != null;
            case ScriptResult script -> script.findings() != null
                    && script.statements() != null && script.routines() != null;
            default -> false;
        };
    }

    private void keep(SourceUnit unit, String key, List<String> dependencies, Object result) {
        kept.put(unit.relPath(), new Entry(unit.relPath(), key, dependencies,
                digestOf(dependencies), null, result));
    }

    // ---- keys ----

    /** The asset's path, its bytes and the code page it is read in. */
    private String ownKey(SourceUnit unit) {
        return unit.absPath() + "|" + contentOf(unit);
    }

    private String cobolKey(SourceUnit unit) {
        if (cobolEnvironment == null) {
            StringBuilder environment = new StringBuilder();
            for (Path directory : set.copybookSearchPaths()) {
                environment.append(directory.toAbsolutePath().normalize()).append('{');
                listing(directory, environment);
                environment.append('}');
            }
            cobolEnvironment = Paths.sha256(environment.append(overrides())
                    .toString().getBytes(StandardCharsets.UTF_8));
        }
        return ownKey(unit) + "|" + cobolEnvironment;
    }

    private String jclKey(SourceUnit unit) {
        if (jclEnvironment == null) {
            StringBuilder environment = new StringBuilder();
            set.unitsOf(AssetKind.JCL).forEach(jcl -> environment.append(jcl.relPath()).append('\n'));
            for (Path directory : set.options().procedureLibraryPaths()) {
                environment.append(directory.toAbsolutePath().normalize()).append('{');
                listing(directory, environment);
                environment.append('}');
            }
            jclEnvironment = Paths.sha256(environment.append(overrides())
                    .toString().getBytes(StandardCharsets.UTF_8));
        }
        return ownKey(unit) + "|" + jclEnvironment;
    }

    /**
     * The paths of the regular files under one directory, relative to it and in name order. The
     * content of a copybook stands in the dependencies of the programs that copy it.
     */
    private static void listing(Path directory, StringBuilder out) {
        TreeSet<String> names = names(directory);
        if (names == null) {
            out.append("unreadable");
            return;
        }
        for (String name : names) {
            out.append(name).append('\n');
        }
    }

    /**
     * The paths of the regular files under a directory, subdirectories included, relative to it
     * and in name order; null when it cannot be read.
     */
    private static TreeSet<String> names(Path directory) {
        TreeSet<String> names = new TreeSet<>();
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.filter(Files::isRegularFile)
                    .forEach(entry -> names.add(directory.relativize(entry).toString()));
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return names;
    }


    private String overrides() {
        return new TreeMap<>(set.options().codepageOverrides()).toString();
    }

    /** The digest of the current content of the files a result read besides its own asset. */
    private String digestOf(List<String> files) {
        if (files.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String path : files) {
            // Many programs copy one copybook; its content is read once a run.
            String content = contentByPath.computeIfAbsent(path, key -> {
                try {
                    return contentOf(Path.of(key));
                } catch (InvalidPathException e) {
                    return "-";
                }
            });
            text.append(path).append('=').append(content).append('\n');
        }
        return Paths.sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A file's content and the code page it would be read in: from the bytes of the walk for an
     * asset of it, from the disk for any other file, and "-" for a file that cannot be read.
     */
    private String contentOf(Path path) {
        SourceUnit unit = unitByPath.get(Paths.normalisedKey(path.toString()));
        if (unit != null) {
            return contentOf(unit);
        }
        try {
            return Paths.sha256(Files.readAllBytes(path)) + ":"
                    + set.options().codepageOverrides().get(path.getFileName().toString());
        } catch (IOException | RuntimeException e) {
            return "-";
        }
    }

    private String contentOf(SourceUnit unit) {
        byte[] bytes = set.bytes().get(unit.relPath());
        return (bytes == null ? "-" : Paths.sha256(bytes)) + ":" + Decode.overrideOf(set, unit);
    }

    private static String engineFingerprint() {
        Path ownClasses;
        try {
            ownClasses = Path.of(ParseCache.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
        } catch (Exception e) {
            ownClasses = null;
        }
        return engineFingerprint(ParseCache.class.getPackage().getImplementationVersion(),
                ownClasses, System.getProperty("java.class.path", ""));
    }

    /**
     * The engine that computed the results: its version and the name, size and time of every jar
     * in the directory the jar holding the engine's own classes was loaded from. That is the lib
     * directory of an installed distribution, the app directory of the packaged image, and the
     * directory of the jar {@code java -jar} names, however the class path was spelled. When the
     * engine's classes come from a directory, as in a build, the entries of {@code classPath}
     * stand in: a jar by its size and time, a directory by the size and time of every file in it,
     * a wildcard entry by the jars of its directory.
     */
    static String engineFingerprint(String version, Path ownClasses, String classPath) {
        StringBuilder text = new StringBuilder(FORMAT).append('\n').append(version).append('\n');
        if (ownClasses != null && Files.isRegularFile(ownClasses)
                && ownClasses.getParent() != null) {
            describeJars(ownClasses.getParent(), text);
        } else {
            for (String entry : classPath.split(File.pathSeparator)) {
                try {
                    if (entry.endsWith("*")) {
                        describeJars(Path.of(entry.substring(0, entry.length() - 1)), text);
                    } else {
                        describe(Path.of(entry), text);
                    }
                } catch (RuntimeException e) {
                    text.append(entry).append(" -\n");
                }
            }
        }
        return Paths.sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void describeJars(Path directory, StringBuilder text) {
        TreeSet<Path> jars = new TreeSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.jar")) {
            entries.forEach(jars::add);
        } catch (IOException | RuntimeException e) {
            text.append(directory).append(" -\n");
            return;
        }
        jars.forEach(jar -> describe(jar, text));
    }

    /** A file's path, size and time; for a directory, those of every file in it. */
    private static void describe(Path path, StringBuilder text) {
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                BasicFileAttributes attributes =
                        Files.readAttributes(file, BasicFileAttributes.class);
                text.append(file).append(' ').append(attributes.size()).append(' ')
                        .append(attributes.lastModifiedTime().toMillis()).append('\n');
            }
        } catch (IOException | RuntimeException e) {
            text.append(path).append(" -\n");
        }
    }

    // ---- the file ----

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<String, Entry> ours = new HashMap<>();
        Map<String, List<Entry>> others = new LinkedHashMap<>();
        try (InputStream stream = new BufferedInputStream(Files.newInputStream(file));
                DataInputStream in = new DataInputStream(stream)) {
            if (!FORMAT.equals(in.readUTF()) || !engine.equals(in.readUTF())) {
                return;
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String entryRoot = in.readUTF();
                String relPath = in.readUTF();
                String key = in.readUTF();
                // Counts and lengths come from the file, so nothing is sized from them ahead of
                // the bytes that back them: a damaged count runs out of bytes and reads as empty.
                int dependencyCount = in.readInt();
                List<String> dependencies = new ArrayList<>();
                for (int d = 0; d < dependencyCount; d++) {
                    dependencies.add(in.readUTF());
                }
                String dependencyDigest = in.readUTF();
                int length = in.readInt();
                if (length < 0) {
                    throw new IOException("a negative length");
                }
                byte[] value = in.readNBytes(length);
                if (value.length != length) {
                    throw new EOFException();
                }
                Entry entry = new Entry(relPath, key, dependencies, dependencyDigest, value, null);
                entry.checksum = in.readUTF();
                if (entryRoot.equals(root)) {
                    ours.put(relPath, entry);
                } else {
                    others.computeIfAbsent(entryRoot, r -> new ArrayList<>()).add(entry);
                }
            }
        } catch (IOException | RuntimeException e) {
            // A cache that cannot be read is an empty one; the scan parses everything.
            return;
        }
        stored.putAll(ours);
        otherRoots.putAll(others);
    }

    /**
     * Writes the results this scan kept, and those of the other folders of {@code roots}, the
     * folders the project file still holds rows of, through a temporary file of the same folder
     * moved into place, so a reader never meets half a file. A result that cannot be encoded is
     * left out, and that asset is parsed again next time.
     */
    void save(List<String> roots) {
        Map<String, List<Entry>> all = new TreeMap<>();
        otherRoots.forEach((otherRoot, entries) -> {
            if (roots.contains(otherRoot)) {
                all.put(otherRoot, entries);
            }
        });
        List<Entry> ours = new ArrayList<>();
        for (Entry entry : kept.values()) {
            if (entry.value == null) {
                if (entry.decoded instanceof CobolResult cobol && cobol.sql() == null) {
                    continue;
                }
                try {
                    entry.value = RecordCodec.encode(entry.decoded);
                } catch (RuntimeException | StackOverflowError e) {
                    // A value nested past what a reader takes back, or past the stack: that
                    // asset is parsed again next time, and the project file is written already.
                    continue;
                }
                entry.checksum = checksumOf(entry);
            }
            ours.add(entry);
        }
        all.put(root, ours);
        Path temporary = null;
        try {
            temporary = Files.createTempFile(file.toAbsolutePath().getParent(),
                    "." + file.getFileName(), ".tmp");
            try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(temporary));
                    DataOutputStream out = new DataOutputStream(stream)) {
                out.writeUTF(FORMAT);
                out.writeUTF(engine);
                out.writeInt(all.values().stream().mapToInt(List::size).sum());
                for (Map.Entry<String, List<Entry>> byRoot : all.entrySet()) {
                    for (Entry entry : byRoot.getValue()) {
                        out.writeUTF(byRoot.getKey());
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
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            // The scan's result is in the project file already; without a cache the next scan
            // parses everything, and nothing else is lost.
            try {
                if (temporary != null) {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException ignored) {
                // Nothing more to do about a temporary file that will not go.
            }
        }
    }
}
