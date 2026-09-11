package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Paths;
import jp.cobolinsight.app.pipeline.Persist;
import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.SourceSet;
import jp.cobolinsight.app.pipeline.SourceUnit;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.json.JsonWriter;
import jp.cobolinsight.core.linemap.LineMappingEntry;
import jp.cobolinsight.core.pipeline.ExitCodes;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.transpile.GeneratedFile;
import jp.cobolinsight.core.transpile.TargetLanguage;
import jp.cobolinsight.core.transpile.TranspileResult;
import jp.cobolinsight.app.persistence.MappingKindCodec;
import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.LineMapRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import jp.cobolinsight.transpile.emit.Transpiler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The core processing behind `translate`. Decodes and parses the COBOL in the asset folder to
 * obtain a {@link CobolSemanticModel}, translates it verbatim into the target language(s)
 * (Python/Java), writes the generated files to the output directory (LF line endings, UTF-8
 * without a BOM), and persists the mapping between COBOL lines and generated lines to LINE_MAP.
 * The COBOL source a mapping resolves to spans not just the main program but copybooks too, so
 * both the main program and copybooks are registered as SOURCE before LINE_MAP is written to
 * satisfy the foreign key. This is deterministic: the transpiler emits its output deterministically,
 * SOURCE.id follows lexicographic path order, LINE_MAP.id is a stable per-source sequence number,
 * and re-running the mapping deletes and rewrites it per source. Decode/parse failures are treated
 * as error-level findings and reflected in the exit code.
 */
public final class TranspileRunner {

    /** The stride used to derive LINE_MAP row ids (source id x STRIDE + sequence number). */
    private static final long LINE_MAP_ID_STRIDE = 1_000_000L;
    private static final String DECODE_FAILURE_RULE_ID = "decode-failure";

    public record Options(Path inputDir, Path databaseFile, List<Path> copybookSearchPaths,
            Map<String, String> codepageOverrides, List<TargetLanguage> languages, Path outputDir) {

        public Options {
            copybookSearchPaths = List.copyOf(copybookSearchPaths);
            languages = List.copyOf(languages);
        }
    }

    public record Result(List<String> analyzed, List<Finding> findings, List<String> generatedFiles,
            int lineMapCount, int exitCode) {

        public Result {
            analyzed = List.copyOf(analyzed);
            findings = List.copyOf(findings);
            generatedFiles = List.copyOf(generatedFiles);
        }

        public String summaryJson(String outputDir) {
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writeArray(writer, "analyzed", analyzed);
            writeArray(writer, "generatedFiles", generatedFiles);
            writer.name("outputDir").value(outputDir.replace('\\', '/'))
                    .name("lineMapCount").value(lineMapCount)
                    .name("findingCount").value(findings.size())
                    .name("errors").value(count(FindingLevel.ERROR))
                    .name("exitCode").value(exitCode)
                    .endObject();
            return writer.toString();
        }

        private long count(FindingLevel level) {
            return findings.stream().filter(f -> f.level() == level).count();
        }

        private static void writeArray(JsonWriter writer, String name, List<String> values) {
            writer.name(name).beginArray();
            for (String value : values) {
                writer.value(value);
            }
            writer.endArray();
        }
    }

    private TranspileRunner() {
    }

    public static Result run(Options options) {
        SourceSet s = Pipelines.translate(options.inputDir(), options.copybookSearchPaths(),
                options.codepageOverrides());
        LintRunner.reportWarnings(s);

        // Transpiling and writing the generated files: pure translation plus I/O, no database.
        List<String> analyzed = new ArrayList<>();
        Set<String> generatedFiles = new TreeSet<>();
        List<LineMappingEntry> allEntries = new ArrayList<>();
        for (SourceUnit unit : s.unitsOf(AssetKind.COBOL)) {
            CobolSemanticModel model = s.programsByPath().get(unit.relPath());
            DecodedSource decoded = s.decoded().get(unit.relPath());
            if (model == null || decoded == null) {
                continue;
            }
            analyzed.add(unit.relPath());
            Transpiler.Ir ir = Transpiler.buildIr(model, decoded.text());
            for (TargetLanguage language : options.languages()) {
                TranspileResult result = Transpiler.transpile(model, ir, language);
                for (GeneratedFile generated : result.files()) {
                    writeGenerated(options.outputDir(), generated);
                    generatedFiles.add(generated.fileName());
                }
                allEntries.addAll(result.lineMap());
            }
        }

        List<Finding> findings = List.copyOf(s.findings());
        int lineMapCount = persist(options, s, allEntries);
        return new Result(analyzed, findings, new ArrayList<>(generatedFiles), lineMapCount,
                ExitCodes.fromFindings(findings));
    }

    // ---- Writing the generated files ----

    private static void writeGenerated(Path outputDir, GeneratedFile generated) {
        try {
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve(generated.fileName()), generated.content(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- Persistence (SOURCE registration and LINE_MAP writing) ----

    /**
     * Get-or-creates the main program and copybooks in SOURCE (an id is kept if the path already
     * matches an existing DB row), then links each mapping entry to the id of its originating
     * source (resolved by basename) and writes it to LINE_MAP. For idempotency across re-runs,
     * existing rows for a source being written are deleted first. Entries that cannot satisfy the
     * foreign key (an unregistered basename) are skipped with a warning.
     */
    private static int persist(Options options, SourceSet set, List<LineMappingEntry> entries) {
        try (PersistenceDatabase database = PersistenceDatabase.open(options.databaseFile())) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            int[] count = {0};
            dao.inTransaction(() -> count[0] = writeLineMaps(dao,
                    Paths.rootOf(options.inputDir()), set, entries));
            return count[0];
        }
    }

    private static int writeLineMaps(PersistenceDao dao, String root, SourceSet set,
            List<LineMappingEntry> entries) {
        Map<String, SourceRecord> existingByPath = new LinkedHashMap<>();
        for (SourceRecord source : dao.findSourcesByRoot(root)) {
            existingByPath.put(source.path(), source);
        }
        long maxId = dao.maxSourceId();
        // Same invariant as Persist: a SOURCE.id at or past this bound would put the line-map rows
        // (id × LINE_MAP_ID_STRIDE + seq) inside the graph layer, which the next scan wipes.
        if (maxId >= Persist.GRAPH_ID_BASE / LINE_MAP_ID_STRIDE) {
            throw new IllegalStateException("プロジェクトファイルに登録できる資産数の上限（"
                    + Persist.GRAPH_ID_BASE / LINE_MAP_ID_STRIDE + "件）に達しました。"
                    + "別のプロジェクトファイルへ取り込んでください");
        }

        Map<String, Long> idByFileName = new TreeMap<>();
        for (SourceUnit unit : set.units()) {
            SourceRecord existing = existingByPath.get(unit.relPath());
            long id;
            if (existing != null) {
                id = existing.id();
            } else {
                id = ++maxId;
                DecodedSource decoded = set.decoded().get(unit.relPath());
                byte[] bytes = set.bytes().get(unit.relPath());
                String codepage = decoded == null ? null : decoded.encoding().detectedCharset();
                dao.insertSource(new SourceRecord(id, root, unit.relPath(), codepage,
                        Paths.sha256(bytes), bytes.length));
            }
            idByFileName.putIfAbsent(unit.fileName(), id);
        }

        // Group entries by their originating source (basename). A basename that cannot be resolved is skipped, since it cannot satisfy the foreign key.
        Map<Long, List<LineMappingEntry>> entriesBySourceId = new TreeMap<>();
        for (LineMappingEntry entry : entries) {
            Long sourceId = idByFileName.get(entry.cobolSourceId());
            if (sourceId == null) {
                System.err.println("警告: 行対応の由来の原始プログラムを SOURCE に特定できないため保存しません: "
                        + entry.cobolSourceId() + " (" + entry.anchorId() + ")");
                continue;
            }
            entriesBySourceId.computeIfAbsent(sourceId, k -> new ArrayList<>()).add(entry);
        }

        int total = 0;
        for (Map.Entry<Long, List<LineMappingEntry>> group : entriesBySourceId.entrySet()) {
            long sourceId = group.getKey();
            dao.deleteLineMapsBySource(sourceId);
            List<LineMappingEntry> sorted = new ArrayList<>(group.getValue());
            sorted.sort(ENTRY_ORDER);
            long seq = 0;
            for (LineMappingEntry entry : sorted) {
                dao.insertLineMap(new LineMapRecord(sourceId * LINE_MAP_ID_STRIDE + (++seq),
                        sourceId, entry.cobolLines().startLine(), entry.cobolLines().endLine(),
                        entry.generatedFile(), entry.generatedLines().startLine(),
                        entry.generatedLines().endLine(), MappingKindCodec.toWire(entry.mappingKind()),
                        entry.note(), entry.anchorId()));
                total++;
            }
        }
        return total;
    }

    /** The total order used to deterministically assign mapping ids within a source (generated file -> generated line -> COBOL line -> anchor). */
    private static final Comparator<LineMappingEntry> ENTRY_ORDER =
            Comparator.comparing(LineMappingEntry::generatedFile)
                    .thenComparingInt(e -> e.generatedLines().startLine())
                    .thenComparingInt(e -> e.generatedLines().endLine())
                    .thenComparingInt(e -> e.cobolLines().startLine())
                    .thenComparingInt(e -> e.cobolLines().endLine())
                    .thenComparing(LineMappingEntry::anchorId);

}
