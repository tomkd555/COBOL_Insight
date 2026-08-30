package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.source.AssetKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Discovers analysis-target sources from the asset folder. Only {@link Discover} calls this class.
 *
 * <p>The walk does not care about the folder's shape. It recurses under INPUT_DIR, and the kind is
 * <b>derived backwards from the source content</b> ({@link SourceClassifier}). The extension plays
 * only two supporting roles. One is narrowing candidates: extensions that are certainly not text by
 * format, and extensions this tool itself writes out, are never read even one byte. The other is a
 * clue when content alone cannot decide: a known extension then determines the kind. When the
 * extension and content disagree, content wins, and the disagreement is reported.
 *
 * <p>The reporting priority is "silently dropped &gt; silently reinterpreted &gt; truncation."
 * {@link Result#undecided()}, {@link Result#mismatches()}, and {@link Result#unreadable()} all
 * return every entry and keep no separate count (the array length is the count). Keeping a count
 * separate from the examples would tempt truncating the examples here, and the user would then have
 * no way to know whether more exist.
 *
 * <p>The recursion guard is not a depth number but a record of the real paths walked
 * ({@link Path#toRealPath}). A depth limit has no justification and would silently drop a user's
 * asset placed deep in the tree. The only real need for a limit is to break directory cycles
 * (a link back to itself), and tracking visited real paths is enough for that.
 */
public final class SourceDiscovery {

    /** The basis on which the kind was decided. */
    public enum Evidence {
        /** Decided from the source content. */
        CONTENT,
        /** Content could not decide it, so decided from the extension. */
        EXTENSION
    }

    /** One discovered file. relPath is the path relative to INPUT_DIR, with separators normalized to /. */
    public record DiscoveredFile(String relPath, Path absPath, AssetKind kind, Evidence evidence) {

        public String fileName() {
            return absPath.getFileName().toString();
        }
    }

    /** One case where the extension and content disagreed. The scan target is filed under the content's kind. */
    public record KindMismatch(String relPath, AssetKind byExtension, AssetKind byContent) {
    }

    /**
     * The scan result. files is ordered lexicographically by relative path; this order is the
     * premise behind the invariant that assigns SOURCE.id in path order. transactionTables holds the
     * absolute paths of CSV files recognized as CICS transaction definition tables.
     */
    public record Result(List<DiscoveredFile> files, List<Path> transactionTables, boolean truncated,
            List<String> undecided, List<KindMismatch> mismatches, List<String> unreadable) {

        public Result {
            files = List.copyOf(files);
            transactionTables = List.copyOf(transactionTables);
            undecided = List.copyOf(undecided);
            mismatches = List.copyOf(mismatches);
            unreadable = List.copyOf(unreadable);
        }

        /** Extracts only the specified kinds. Which kinds are needed differs per Runner, so callers narrow it. */
        public List<DiscoveredFile> filesOf(Set<AssetKind> kinds) {
            return files.stream().filter(f -> kinds.contains(f.kind())).toList();
        }

        /**
         * Messages that convey dropped files and reinterpretations to the user. scan writes them into
         * the summary JSON; lint, sql-lint, translate, and fix write them to standard error. Messages
         * are gathered here because there is only one entry point for scanning, so there should also
         * be only one entry point for reporting it.
         */
        public List<String> warnings() {
            List<String> messages = new ArrayList<>();
            if (!undecided.isEmpty()) {
                messages.add(undecided.size() + "件は種別を判定できなかったため対象から外した。"
                        + "COBOL 本体なら IDENTIFICATION DIVISION、コピー句ならレベル番号で始まる"
                        + "項目定義、JCL なら // で始まる行、BMS なら DFHMSD を含むか確認する: "
                        + String.join(", ", undecided));
            }
            if (!mismatches.isEmpty()) {
                List<String> details = mismatches.stream()
                        .map(m -> m.relPath() + " (拡張子は" + label(m.byExtension())
                                + "、内容は" + label(m.byContent()) + ")")
                        .toList();
                messages.add(mismatches.size()
                        + "件は拡張子と内容が食い違ったため、内容を優先して取り込んだ: "
                        + String.join(", ", details));
            }
            if (!unreadable.isEmpty()) {
                messages.add(unreadable.size() + "件は読み取れなかったため対象から外した: "
                        + String.join(", ", unreadable));
            }
            if (truncated) {
                messages.add("走査するファイル数が上限(" + MAX_FILES
                        + "件)に達したため、以降のファイルを対象から外した。"
                        + "資産フォルダを分けて取り込む。");
            }
            return messages;
        }

        private static String label(AssetKind kind) {
            return switch (kind) {
                case BMS -> "BMSマップ";
                case COBOL -> "COBOL本体";
                case COPYBOOK -> "コピー句";
                case JCL -> "JCL";
            };
        }
    }

    /**
     * The upper limit on the number of scanned files. This is an external constraint coming from the
     * invariant that SOURCE.id stays under 1,000,000 ({@link Persist}'s class Javadoc), not a number
     * this tool chose. When the limit is reached, {@link Result#truncated()} must convey it to the
     * user.
     */
    public static final int MAX_FILES = 5_000;

    /** Directory names the recursive walk does not descend into. Names starting with . are also excluded. */
    private static final Set<String> EXCLUDED_DIRS =
            Set.of("node_modules", "build", "target", "out", "dist");

    /**
     * Extensions never treated as candidates. Decided not by count but by exactly two criteria: ones
     * certainly not text by format (images, archives, executables, SQLite DBs), and ones certainly a
     * document about an asset rather than an asset itself by format (SARIF/HTML/text reports and JSON
     * this tool writes out, and Markdown documentation placed in the asset folder). Extensions outside
     * these criteria are treated as unknown and judged by content.
     *
     * <p>.md is dropped because prose that merely contains the word PROGRAM-ID must not turn into an
     * analysis target. {@code samples/expected-results.md} is a real example: a document describing an
     * asset, written with COBOL terms in its body, is placed in the asset folder. Since a document is
     * not any kind of asset, it is treated as outside the candidates rather than as unclassifiable.
     */
    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".tif", ".tiff", ".webp",
            ".zip", ".jar", ".war", ".ear", ".gz", ".tar", ".7z", ".rar",
            ".exe", ".dll", ".so", ".dylib", ".class", ".o", ".obj", ".bin", ".pdf",
            ".sarif", ".html", ".txt", ".json", ".md", ".db");

    /** The extension treated as a candidate transaction definition table. */
    private static final String TRANSACTION_TABLE_EXTENSION = ".csv";

    /**
     * The value format (asset name) for a transaction definition table. The 8-character limit and the
     * allowed characters follow the mainframe member-name convention. This is kept as the single
     * source of truth so that the judgment of whether something counts as a table and the row reading
     * ({@link Persist}) see the same rule.
     */
    public static final Pattern MEMBER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9@#$-]{1,8}");

    private SourceDiscovery() {
    }

    /** Scans the asset folder. Returns an empty result if a nonexistent folder is passed. */
    public static Result discover(Path inputDir) {
        Walk walk = new Walk(inputDir);
        walk.walk(inputDir);
        walk.files.sort(Comparator.comparing(DiscoveredFile::relPath));
        walk.transactionTables.sort(Comparator.naturalOrder());
        walk.undecided.sort(Comparator.naturalOrder());
        walk.mismatches.sort(Comparator.comparing(KindMismatch::relPath));
        walk.unreadable.sort(Comparator.naturalOrder());
        return new Result(walk.files, walk.transactionTables, walk.truncated,
                walk.undecided, walk.mismatches, walk.unreadable);
    }

    /** Working state for the recursive walk. */
    private static final class Walk {

        private final Path inputDir;
        /** Real paths of visited directories. Cycles caused by symbolic links are cut off here. */
        private final Set<Path> visited = new HashSet<>();
        private final List<DiscoveredFile> files = new ArrayList<>();
        private final List<Path> transactionTables = new ArrayList<>();
        private final List<String> undecided = new ArrayList<>();
        private final List<KindMismatch> mismatches = new ArrayList<>();
        private final List<String> unreadable = new ArrayList<>();
        private boolean truncated;

        private Walk(Path inputDir) {
            this.inputDir = inputDir;
        }

        private void walk(Path dir) {
            if (truncated || !Files.isDirectory(dir)) {
                return;
            }
            Path real;
            try {
                real = dir.toRealPath();
            } catch (IOException e) {
                // A directory whose real path cannot be obtained cannot be checked for cycles.
                // Do not descend into it, and report it as a dropped item.
                unreadable.add(relativize(dir));
                return;
            }
            if (!visited.add(real)) {
                return;
            }
            List<Path> children;
            try (Stream<Path> stream = Files.list(dir)) {
                children = stream.sorted().toList();
            } catch (IOException e) {
                unreadable.add(relativize(dir));
                return;
            }
            for (Path child : children) {
                if (truncated) {
                    return;
                }
                if (Files.isDirectory(child)) {
                    if (!isExcluded(child)) {
                        walk(child);
                    }
                } else if (Files.isRegularFile(child)) {
                    consider(child);
                }
            }
        }

        private boolean isExcluded(Path dir) {
            String name = dir.getFileName().toString();
            return name.startsWith(".") || EXCLUDED_DIRS.contains(name.toLowerCase(Locale.ROOT));
        }

        private void consider(Path file) {
            String extension = AssetKind.extensionOf(file.getFileName().toString());
            if (TRANSACTION_TABLE_EXTENSION.equals(extension)) {
                if (looksLikeTransactionTable(file)) {
                    transactionTables.add(file);
                }
                return;
            }
            if (EXCLUDED_EXTENSIONS.contains(extension)) {
                return;
            }
            byte[] content;
            try {
                content = Files.readAllBytes(file);
            } catch (IOException e) {
                unreadable.add(relativize(file));
                return;
            }
            SourceClassifier.Verdict verdict = SourceClassifier.classify(content);
            AssetKind byExtension = AssetKind.ofExtension(extension);
            AssetKind byContent = verdict.kind();
            if (byContent == null && byExtension == null) {
                // A file containing NUL is a confirmed result of "not text," not something that
                // could not be classified. It is not a dropped item, so it is not added to undecided.
                if (!verdict.binary()) {
                    undecided.add(relativize(file));
                }
                return;
            }
            AssetKind kind = byContent != null ? byContent : byExtension;
            Evidence evidence = byContent != null ? Evidence.CONTENT : Evidence.EXTENSION;
            if (byContent != null && byExtension != null && byContent != byExtension) {
                mismatches.add(new KindMismatch(relativize(file), byExtension, byContent));
            }
            if (files.size() >= MAX_FILES) {
                truncated = true;
                return;
            }
            files.add(new DiscoveredFile(relativize(file), file, kind, evidence));
        }

        /**
         * Whether this can be treated as a CICS transaction definition table. The first line is
         * skipped as a header, and the condition is that at least one pair from line two onward has
         * "both columns matching the asset-name format." There is no harm in treating an unrelated
         * CSV as a table, because {@code CallGraphLinker} only queries IDs the COBOL side actually
         * referenced, so extra rows do not contribute to the graph.
         */
        private static boolean looksLikeTransactionTable(Path csv) {
            List<String> lines;
            try {
                lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // A CSV that cannot be read as UTF-8 is not a definition table this tool reads. It is
                // not an asset source either, so there is no need to tell the user it was excluded.
                return false;
            }
            for (String line : lines.stream().skip(1).toList()) {
                String[] fields = line.split(",");
                if (fields.length == 2
                        && MEMBER_NAME_PATTERN.matcher(fields[0].trim()).matches()
                        && MEMBER_NAME_PATTERN.matcher(fields[1].trim()).matches()) {
                    return true;
                }
            }
            return false;
        }

        /** Every walked file lies under the asset folder, so Paths.relativize is exact here. */
        private String relativize(Path file) {
            return Paths.relativize(inputDir, file);
        }
    }
}
