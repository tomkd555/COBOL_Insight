package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.json.JsonWriter;
import jp.cobolinsight.core.linemap.LineMappingEntry;
import jp.cobolinsight.core.pipeline.AnalysisServices;
import jp.cobolinsight.core.pipeline.ExitCodes;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.CharsetProvider;
import jp.cobolinsight.core.spi.CobolParser;
import jp.cobolinsight.core.spi.ParseOutcome;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * `translate` の中核処理。資産フォルダの COBOL を復号・パースして {@link CobolSemanticModel} を得て、
 * 対象言語(Python/Java)へ逐語対訳し、生成ファイルを出力先へ(改行 LF・BOM なし UTF-8 で)書き、
 * COBOL 行と生成行の対応を LINE_MAP へ永続化する。対応先の COBOL ソースは本体だけでなくコピー句にも
 * 及ぶため、本体・コピー句の双方を SOURCE として登録してから外部キーを満たす形で LINE_MAP を書く。
 * 決定論とする: 生成物は逐語対訳器が決定論で出し、SOURCE.id はパスの辞書順、LINE_MAP.id は
 * ソース id ごとの安定順の連番、行対応の再実行はソース単位で消去してから書き直す。
 * 復号・パース失敗は error レベルの finding として扱い、終了コードへ反映する。
 */
public final class TranspileRunner {

    /** LINE_MAP の行 id 導出の刻み幅(ソース id×STRIDE+連番)。 */
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

    private enum SourceKind {
        COBOL, COPYBOOK
    }

    private record TranspileFile(String relPath, String fileName, Path absPath, SourceKind kind) {
    }

    private TranspileRunner() {
    }

    public static Result run(Options options) {
        AnalysisServices services = AnalysisServices.load();
        CharsetProvider charsetProvider = first(services.charsetProviders(), "CharsetProvider");
        CobolParser cobolParser = first(services.cobolParsers(), "CobolParser");

        List<TranspileFile> files = discover(options);
        List<Finding> findings = new ArrayList<>();
        Map<String, byte[]> bytesByRel = new LinkedHashMap<>();
        Map<String, DecodedSource> decodedByRel = new LinkedHashMap<>();
        for (TranspileFile file : files) {
            byte[] bytes = readBytes(file.absPath());
            bytesByRel.put(file.relPath(), bytes);
            try {
                decodedByRel.put(file.relPath(), decode(charsetProvider, options, file, bytes));
            } catch (IllegalArgumentException e) {
                if (file.kind() == SourceKind.COBOL) {
                    findings.add(Finding.of(DECODE_FAILURE_RULE_ID, FindingLevel.ERROR,
                            "復号に失敗した: " + e.getMessage(),
                            SourcePosition.fileStart(file.relPath())));
                }
            }
        }

        // 逐語対訳と生成ファイルの出力(DB を介さない純粋処理と I/O)。
        List<String> analyzed = new ArrayList<>();
        Set<String> generatedFiles = new TreeSet<>();
        List<LineMappingEntry> allEntries = new ArrayList<>();
        for (TranspileFile file : files) {
            DecodedSource decoded = decodedByRel.get(file.relPath());
            if (file.kind() != SourceKind.COBOL || decoded == null) {
                continue;
            }
            ParseOutcome<CobolSemanticModel> outcome =
                    cobolParser.parse(decoded, options.copybookSearchPaths());
            if (outcome instanceof ParseOutcome.Failure<CobolSemanticModel> failure) {
                findings.add(failure.finding());
                continue;
            }
            CobolSemanticModel model = outcome.value().orElseThrow();
            analyzed.add(file.relPath());
            for (TargetLanguage language : options.languages()) {
                TranspileResult result = Transpiler.transpile(model, decoded.text(), language);
                for (GeneratedFile generated : result.files()) {
                    writeGenerated(options.outputDir(), generated);
                    generatedFiles.add(generated.fileName());
                }
                allEntries.addAll(result.lineMap());
            }
        }

        int lineMapCount = persist(options, files, bytesByRel, decodedByRel, allEntries);

        int exitCode = ExitCodes.fromFindings(findings);
        return new Result(analyzed, findings, new ArrayList<>(generatedFiles), lineMapCount,
                exitCode);
    }

    // ---- 走査 ----

    /**
     * scan と同じ走査で COBOL 本体を、コピー句探索パス配下からコピー句を発見する(相対パスの
     * 辞書順)。コピー句は入力フォルダの外を指せるため、走査ではなく探索パスを起点とする
     * ({@link CopybookScan})。走査の取りこぼしと解釈の変更は標準エラーへ出す。
     */
    private static List<TranspileFile> discover(Options options) {
        SourceDiscovery.Result discovery = SourceDiscovery.discover(options.inputDir());
        LintRunner.reportDiscoveryWarnings(discovery);
        Map<String, TranspileFile> byRel = new TreeMap<>();
        for (SourceDiscovery.DiscoveredFile file : discovery.filesOf(Set.of(AssetKind.COBOL))) {
            byRel.putIfAbsent(file.relPath(), new TranspileFile(file.relPath(),
                    file.fileName(), file.absPath(), SourceKind.COBOL));
        }
        for (Path dir : options.copybookSearchPaths()) {
            for (Path copybook : CopybookScan.collect(dir)) {
                String relPath = relativize(options.inputDir(), copybook);
                byRel.putIfAbsent(relPath, new TranspileFile(relPath,
                        copybook.getFileName().toString(), copybook, SourceKind.COPYBOOK));
            }
        }
        return new ArrayList<>(byRel.values());
    }

    /** 入力フォルダ配下なら相対パス、そうでなければ「親ディレクトリ名/ファイル名」を相対パスとする。 */
    private static String relativize(Path inputDir, Path file) {
        Path base = inputDir.toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        if (abs.startsWith(base)) {
            return base.relativize(abs).toString().replace('\\', '/');
        }
        Path parent = abs.getParent();
        String dirName = parent == null ? "" : parent.getFileName().toString();
        return (dirName.isEmpty() ? "" : dirName + "/") + abs.getFileName();
    }

    private static DecodedSource decode(CharsetProvider charsetProvider, Options options,
            TranspileFile file, byte[] bytes) {
        String override = options.codepageOverrides().get(file.relPath());
        if (override == null) {
            override = options.codepageOverrides().get(file.fileName());
        }
        return override == null
                ? charsetProvider.decode(file.absPath().toString(), bytes)
                : charsetProvider.decode(file.absPath().toString(), bytes, override);
    }

    // ---- 生成ファイルの出力 ----

    private static void writeGenerated(Path outputDir, GeneratedFile generated) {
        try {
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve(generated.fileName()), generated.content(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- 永続化(SOURCE 登録と LINE_MAP 書込)----

    /**
     * 本体・コピー句を SOURCE へ get-or-create(既存 DB のパス一致は id を保つ)し、行対応の各エントリを
     * その由来ソース(basename で解決)の id へ紐づけて LINE_MAP へ書く。再実行の冪等性のため、書き込む
     * ソースの既存行を先に消去する。外部キーを満たせないエントリ(未登録の basename)は警告して飛ばす。
     */
    private static int persist(Options options, List<TranspileFile> files,
            Map<String, byte[]> bytesByRel, Map<String, DecodedSource> decodedByRel,
            List<LineMappingEntry> entries) {
        try (PersistenceDatabase database = PersistenceDatabase.open(options.databaseFile())) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            int[] count = {0};
            dao.inTransaction(() -> count[0] = writeLineMaps(dao, ScanRunner.rootOf(
                    options.inputDir()), files, bytesByRel, decodedByRel, entries));
            return count[0];
        }
    }

    private static int writeLineMaps(PersistenceDao dao, String root, List<TranspileFile> files,
            Map<String, byte[]> bytesByRel, Map<String, DecodedSource> decodedByRel,
            List<LineMappingEntry> entries) {
        Map<String, SourceRecord> existingByPath = new LinkedHashMap<>();
        for (SourceRecord source : dao.findSourcesByRoot(root)) {
            existingByPath.put(source.path(), source);
        }
        long maxId = dao.maxSourceId();

        Map<String, Long> idByFileName = new TreeMap<>();
        for (TranspileFile file : files) {
            SourceRecord existing = existingByPath.get(file.relPath());
            long id;
            if (existing != null) {
                id = existing.id();
            } else {
                id = ++maxId;
                DecodedSource decoded = decodedByRel.get(file.relPath());
                byte[] bytes = bytesByRel.get(file.relPath());
                String codepage = decoded == null ? null : decoded.encoding().detectedCharset();
                dao.insertSource(new SourceRecord(id, root, file.relPath(), codepage, sha256(bytes),
                        bytes.length));
            }
            idByFileName.putIfAbsent(file.fileName(), id);
        }

        // 由来ソース(basename)ごとにエントリをまとめる。解決できない basename は外部キーを満たせないため飛ばす。
        Map<Long, List<LineMappingEntry>> entriesBySourceId = new TreeMap<>();
        for (LineMappingEntry entry : entries) {
            Long sourceId = idByFileName.get(entry.cobolSourceId());
            if (sourceId == null) {
                System.err.println("警告: 行対応の由来ソースを SOURCE に特定できないため保存しない: "
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

    /** ソース内で行対応 id を決定論的に振るための全順序(生成ファイル→生成行→COBOL行→アンカー)。 */
    private static final Comparator<LineMappingEntry> ENTRY_ORDER =
            Comparator.comparing(LineMappingEntry::generatedFile)
                    .thenComparingInt(e -> e.generatedLines().startLine())
                    .thenComparingInt(e -> e.generatedLines().endLine())
                    .thenComparingInt(e -> e.cobolLines().startLine())
                    .thenComparingInt(e -> e.cobolLines().endLine())
                    .thenComparing(LineMappingEntry::anchorId);

    // ---- 補助 ----

    private static <T> T first(List<T> implementations, String contractName) {
        if (implementations.isEmpty()) {
            throw new IllegalStateException(contractName + " の実装が実行時クラスパスに無い");
        }
        return implementations.get(0);
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
