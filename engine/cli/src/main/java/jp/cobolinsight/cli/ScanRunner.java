package jp.cobolinsight.cli;

import jp.cobolinsight.bmsfrontend.BmsModelMapper;
import jp.cobolinsight.bmsfrontend.BmsParseError;
import jp.cobolinsight.bmsfrontend.BmsParseResult;
import jp.cobolinsight.bmsfrontend.BmsSourceParser;
import jp.cobolinsight.engineapi.bms.BmsField;
import jp.cobolinsight.engineapi.bms.BmsMap;
import jp.cobolinsight.engineapi.bms.BmsMapset;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.jcl.JclExecKind;
import jp.cobolinsight.engineapi.jcl.JclJobModel;
import jp.cobolinsight.engineapi.jcl.JclStep;
import jp.cobolinsight.engineapi.json.JsonWriter;
import jp.cobolinsight.engineapi.pipeline.AnalysisServices;
import jp.cobolinsight.engineapi.pipeline.ExitCodes;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlock;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlockKind;
import jp.cobolinsight.engineapi.semantic.Procedure;
import jp.cobolinsight.engineapi.source.CopyExpansionEntry;
import jp.cobolinsight.engineapi.source.DecodedSource;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.CharsetProvider;
import jp.cobolinsight.engineapi.spi.CobolParser;
import jp.cobolinsight.engineapi.spi.JclParser;
import jp.cobolinsight.engineapi.spi.ParseOutcome;
import jp.cobolinsight.engineapi.spi.SqlParser;
import jp.cobolinsight.engineapi.sql.SqlStatementModel;
import jp.cobolinsight.engineapi.callgraph.CallGraph;
import jp.cobolinsight.engineapi.callgraph.CallGraphEdge;
import jp.cobolinsight.engineapi.callgraph.CallGraphNode;
import jp.cobolinsight.linker.CallGraphLinker;
import jp.cobolinsight.linker.LinkResult;
import jp.cobolinsight.linker.LinkerInput;
import jp.cobolinsight.persistence.IncrementalAnalysisPlanner;
import jp.cobolinsight.persistence.PersistenceDao;
import jp.cobolinsight.persistence.PersistenceDatabase;
import jp.cobolinsight.persistence.model.BmsFieldRecord;
import jp.cobolinsight.persistence.model.BmsMapRecord;
import jp.cobolinsight.persistence.model.BmsMapsetRecord;
import jp.cobolinsight.persistence.model.CallEdgeRecord;
import jp.cobolinsight.persistence.model.EncodingInfoRecord;
import jp.cobolinsight.persistence.model.FindingRecord;
import jp.cobolinsight.persistence.model.NodeRecord;
import jp.cobolinsight.persistence.model.ParagraphRecord;
import jp.cobolinsight.persistence.model.ProgramRecord;
import jp.cobolinsight.persistence.model.SourceRecord;
import jp.cobolinsight.persistence.model.SqlStmtRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * `scan` の中核処理。資産フォルダを走査し、復号→種別ごとのパース(ServiceLoader経由のSPI)→
 * SQLiteへの保存を行う。ID採番は決定論とする: SOURCEはパスの辞書順で1から振り(既存DBがある
 * 場合は既存IDを保持し、新規ファイルへ最大ID+1から振る)、NODE.id=SOURCE.id 規約を守り、
 * 子表の行IDは「ソースID×1,000,000+連番」で導出する。パース失敗はerror findingとして記録し、
 * 残りのファイルの解析を継続する。
 */
public final class ScanRunner {

    /** 子表(PARAGRAPH・FINDING・SQL_STMT・CALL_EDGE・BMS_MAPSET)の行ID導出の刻み幅。 */
    private static final long ID_STRIDE = 1_000_000L;
    /**
     * 呼出関係グラフ層(ソース非対応ノード・グラフ辺・linker由来finding)のID下限。
     * scan由来の行ID(SOURCE.id および SOURCE.id×{@link #ID_STRIDE}+連番)と衝突しない値とし、
     * グラフ層はscanのたびにこの下限以上を全消去して再構築する。
     * 不変条件: SOURCE.id は {@code GRAPH_ID_BASE / ID_STRIDE}(=1,000,000)未満であること。
     * これを超えると scan 由来の子表行IDがグラフ層のID帯へ食い込み、グラフ再構築時の
     * 全消去に巻き込まれる。
     */
    static final long GRAPH_ID_BASE = 1_000_000_000_000L;
    private static final String DECODE_FAILURE_RULE_ID = "decode-failure";

    public record Options(Path inputDir, Path databaseFile, List<Path> copybookSearchPaths,
            Map<String, String> codepageOverrides) {
    }

    public record Summary(List<String> analyzed, List<String> skipped, List<String> removed,
            int findingCount, int exitCode) {

        public String toJson() {
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writeArray(writer, "analyzed", analyzed);
            writeArray(writer, "skipped", skipped);
            writeArray(writer, "removed", removed);
            writer.name("findingCount").value(findingCount);
            writer.name("exitCode").value(exitCode);
            writer.endObject();
            return writer.toString();
        }

        private static void writeArray(JsonWriter writer, String name, List<String> values) {
            writer.name(name).beginArray();
            for (String value : values) {
                writer.value(value);
            }
            writer.endArray();
        }
    }

    /** scan の全結果。呼出関係グラフと linker 由来の findings(解決根拠の記録)を含む。 */
    public record Result(Summary summary, CallGraph callGraph, List<Finding> linkerFindings) {

        public Result {
            linkerFindings = List.copyOf(linkerFindings);
        }
    }

    private enum SourceKind {
        BMS("BMS"), COBOL("PROGRAM"), COPYBOOK("COPYBOOK"), JCL("JCL");

        final String nodeType;

        SourceKind(String nodeType) {
            this.nodeType = nodeType;
        }
    }

    private record ScanFile(String relPath, Path absPath, SourceKind kind) {

        String fileName() {
            return absPath.getFileName().toString();
        }
    }

    private final Options options;
    private final CharsetProvider charsetProvider;
    private final CobolParser cobolParser;
    private final JclParser jclParser;
    private final SqlParser sqlParser;

    private PersistenceDao dao;
    private final Map<String, Long> idByRel = new LinkedHashMap<>();
    private final Map<Long, DecodedSource> decodedById = new HashMap<>();
    private final Map<Long, CobolSemanticModel> cobolModelsById = new HashMap<>();
    private final Map<Long, JclJobModel> jclModelsById = new TreeMap<>();
    private final Map<Long, List<BmsMapset>> bmsMapsetsById = new TreeMap<>();
    private final Map<String, List<SqlStatementModel>> sqlModelsByProgramId = new TreeMap<>();
    private final Map<Long, Integer> findingSeqBySource = new HashMap<>();
    private final List<Finding> runFindings = new ArrayList<>();
    private LinkResult linkResult;

    private ScanRunner(Options options, AnalysisServices services) {
        this.options = options;
        this.charsetProvider = first(services.charsetProviders(), "CharsetProvider");
        this.cobolParser = first(services.cobolParsers(), "CobolParser");
        this.jclParser = first(services.jclParsers(), "JclParser");
        this.sqlParser = first(services.sqlParsers(), "SqlParser");
    }

    public static Summary run(Options options) {
        return runWithGraph(options).summary();
    }

    /** scan を実行し、処理サマリに加えて呼出関係グラフと linker findings を返す。 */
    public static Result runWithGraph(Options options) {
        return new ScanRunner(options, AnalysisServices.load()).execute();
    }

    private static <T> T first(List<T> implementations, String contractName) {
        if (implementations.isEmpty()) {
            throw new IllegalStateException(contractName + " の実装が実行時クラスパスに無い");
        }
        return implementations.get(0);
    }

    private Result execute() {
        List<ScanFile> files = discover(options.inputDir());
        Map<String, byte[]> bytesByRel = new LinkedHashMap<>();
        Map<String, String> hashByRel = new LinkedHashMap<>();
        for (ScanFile file : files) {
            byte[] bytes = readBytes(file.absPath());
            bytesByRel.put(file.relPath(), bytes);
            hashByRel.put(file.relPath(), sha256(bytes));
        }

        try (PersistenceDatabase database = PersistenceDatabase.open(options.databaseFile())) {
            dao = new PersistenceDao(database.connection());
            Map<String, SourceRecord> existingByPath = new LinkedHashMap<>();
            for (SourceRecord source : dao.findAllSources()) {
                existingByPath.put(source.path(), source);
            }

            // 変更検知と依存伝播(コピー句→取込プログラム、プログラム→呼出JCL)
            IncrementalAnalysisPlanner planner = new IncrementalAnalysisPlanner(dao);
            Set<Long> reanalysisIds = new LinkedHashSet<>();
            for (ScanFile file : files) {
                SourceRecord existing = existingByPath.get(file.relPath());
                if (existing != null) {
                    reanalysisIds.addAll(planner.determineReanalysisTargets(existing.id(),
                            hashByRel.get(file.relPath())));
                }
            }

            long maxId = existingByPath.values().stream().mapToLong(SourceRecord::id).max().orElse(0);
            List<ScanFile> targets = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (ScanFile file : files) {
                SourceRecord existing = existingByPath.get(file.relPath());
                long id = existing != null ? existing.id() : ++maxId;
                idByRel.put(file.relPath(), id);
                if (existing == null || reanalysisIds.contains(id)) {
                    targets.add(file);
                } else {
                    skipped.add(file.relPath());
                }
            }

            List<String> removed = existingByPath.keySet().stream()
                    .filter(path -> !idByRel.containsKey(path)).toList();

            dao.inTransaction(() -> {
                for (String path : removed) {
                    long id = existingByPath.get(path).id();
                    dao.deleteSourceCascade(id);
                    dao.deleteNode(id);
                }
                replaceSources(targets, existingByPath, bytesByRel, hashByRel);
                registerCopybookNodes(targets);
                analyzeCobol(targets);
                analyzeJcl(targets, files);
                analyzeBms(targets);
                ensureAllModels(files, bytesByRel);
                linkResult = linkAndPersistCallGraph();
            });

            // findingCount は復号・パース由来のfindingの件数。linker由来findingは
            // 解決根拠の記録であり件数に含めず、終了コードの判定にのみ加える。
            List<Finding> forExitCode = new ArrayList<>(runFindings);
            forExitCode.addAll(linkResult.findings());
            int persistedFindings = 0;
            int exitCode = ExitCodes.fromFindings(forExitCode);
            for (String path : skipped) {
                for (FindingRecord finding : dao.findFindingsBySource(idByRel.get(path))) {
                    if (finding.id() >= GRAPH_ID_BASE) {
                        continue;
                    }
                    persistedFindings++;
                    if ("ERROR".equals(finding.level())) {
                        exitCode = ExitCodes.ERRORS;
                    } else if ("WARNING".equals(finding.level()) && exitCode == ExitCodes.SUCCESS) {
                        exitCode = ExitCodes.WARNINGS;
                    }
                }
            }
            Summary summary = new Summary(
                    targets.stream().map(ScanFile::relPath).toList(),
                    skipped, removed,
                    runFindings.size() + persistedFindings, exitCode);
            return new Result(summary, linkResult.graph(), linkResult.findings());
        }
    }

    // ---- 走査 ----

    private static List<ScanFile> discover(Path inputDir) {
        Map<String, SourceKind> kindByDir = new LinkedHashMap<>();
        kindByDir.put("bms", SourceKind.BMS);
        kindByDir.put("cobol", SourceKind.COBOL);
        kindByDir.put("copy", SourceKind.COPYBOOK);
        kindByDir.put("copybook", SourceKind.COPYBOOK);
        kindByDir.put("jcl", SourceKind.JCL);
        Map<SourceKind, String> extensionByKind = Map.of(
                SourceKind.BMS, ".bms", SourceKind.COBOL, ".cbl",
                SourceKind.COPYBOOK, ".cpy", SourceKind.JCL, ".jcl");

        List<ScanFile> files = new ArrayList<>();
        for (Map.Entry<String, SourceKind> entry : kindByDir.entrySet()) {
            Path dir = inputDir.resolve(entry.getKey());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            String extension = extensionByKind.get(entry.getValue());
            try (Stream<Path> children = Files.list(dir)) {
                children.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                                .endsWith(extension))
                        .forEach(p -> files.add(new ScanFile(
                                entry.getKey() + "/" + p.getFileName(), p, entry.getValue())));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        files.sort(java.util.Comparator.comparing(ScanFile::relPath));
        return files;
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

    // ---- SOURCE・ENCODING_INFO の入替と復号 ----

    private void replaceSources(List<ScanFile> targets, Map<String, SourceRecord> existingByPath,
            Map<String, byte[]> bytesByRel, Map<String, String> hashByRel) {
        for (ScanFile file : targets) {
            long id = idByRel.get(file.relPath());
            if (existingByPath.containsKey(file.relPath())) {
                dao.deleteSourceCascade(id);
                dao.deleteCallEdgesTo(id, IncrementalAnalysisPlanner.COPY_EDGE_KIND);
                dao.deleteCallEdgesFrom(id, IncrementalAnalysisPlanner.EXECUTION_EDGE_KIND);
            }
            byte[] bytes = bytesByRel.get(file.relPath());
            DecodedSource decoded = null;
            String decodeError = null;
            try {
                decoded = decode(file, bytes);
            } catch (IllegalArgumentException e) {
                decodeError = e.getMessage();
            }
            String codepage = decoded == null ? null : decoded.encoding().detectedCharset();
            dao.insertSource(new SourceRecord(id, file.relPath(), codepage,
                    hashByRel.get(file.relPath()), bytes.length));
            if (decoded != null) {
                decodedById.put(id, decoded);
                dao.insertEncodingInfo(new EncodingInfoRecord(id,
                        decoded.encoding().detectedCharset(), decoded.encoding().confidence(),
                        decoded.encoding().manualOverride(), decoded.encoding().soSiPresent()));
            } else {
                recordFinding(id, file.relPath(), Finding.of(DECODE_FAILURE_RULE_ID,
                        FindingLevel.ERROR, "復号に失敗した: " + decodeError,
                        SourcePosition.fileStart(file.relPath())));
                upsertNode(new NodeRecord(id, file.kind().nodeType, file.fileName()));
            }
        }
    }

    private DecodedSource decode(ScanFile file, byte[] bytes) {
        String override = options.codepageOverrides().get(file.relPath());
        if (override == null) {
            override = options.codepageOverrides().get(file.fileName());
        }
        return override == null
                ? charsetProvider.decode(file.absPath().toString(), bytes)
                : charsetProvider.decode(file.absPath().toString(), bytes, override);
    }

    // ---- 種別ごとの解析 ----

    private void registerCopybookNodes(List<ScanFile> targets) {
        for (ScanFile file : targets) {
            long id = idByRel.get(file.relPath());
            if (file.kind() == SourceKind.COPYBOOK && decodedById.containsKey(id)) {
                upsertNode(new NodeRecord(id, SourceKind.COPYBOOK.nodeType, file.fileName()));
            }
        }
    }

    private void analyzeCobol(List<ScanFile> targets) {
        // Windowsではコピー句解決が探索名の大小を区別しないため、ファイル名照合も大小無視とする
        Map<String, Long> copybookIdByFileName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, Long> entry : idByRel.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).endsWith(".cpy")) {
                String fileName = entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1);
                copybookIdByFileName.put(fileName, entry.getValue());
            }
        }
        for (ScanFile file : targets) {
            long id = idByRel.get(file.relPath());
            if (file.kind() != SourceKind.COBOL || !decodedById.containsKey(id)) {
                continue;
            }
            ParseOutcome<CobolSemanticModel> outcome =
                    cobolParser.parse(decodedById.get(id), options.copybookSearchPaths());
            if (outcome instanceof ParseOutcome.Failure<CobolSemanticModel> failure) {
                recordFinding(id, file.relPath(), failure.finding());
                upsertNode(new NodeRecord(id, SourceKind.COBOL.nodeType, file.fileName()));
                continue;
            }
            CobolSemanticModel model = outcome.value().orElseThrow();
            cobolModelsById.put(id, model);
            upsertNode(new NodeRecord(id, SourceKind.COBOL.nodeType, model.programId()));
            dao.insertProgram(new ProgramRecord(id, id, model.programId()));
            long paragraphSeq = 0;
            for (Procedure procedure : model.procedures()) {
                dao.insertParagraph(new ParagraphRecord(id * ID_STRIDE + (++paragraphSeq), id,
                        procedure.name(), procedure.range().start().line(),
                        procedure.range().end().line()));
            }
            persistSqlStatements(id, file.relPath(), model);
            persistCopyEdges(id, model, copybookIdByFileName);
        }
    }

    private void persistSqlStatements(long sourceId, String relPath, CobolSemanticModel model) {
        long sqlSeq = 0;
        for (EmbeddedBlock block : model.embeddedBlocks()) {
            if (block.kind() != EmbeddedBlockKind.SQL) {
                continue;
            }
            ParseOutcome<SqlStatementModel> outcome = sqlParser.parse(block);
            if (outcome instanceof ParseOutcome.Failure<SqlStatementModel> failure) {
                recordFinding(sourceId, relPath, failure.finding());
                continue;
            }
            SqlStatementModel statement = outcome.value().orElseThrow();
            dao.insertSqlStmt(new SqlStmtRecord(sourceId * ID_STRIDE + (++sqlSeq), sourceId,
                    statement.kind().name(), statement.mangledText(), statement.originalText()));
            sqlModelsByProgramId.computeIfAbsent(model.programId(), k -> new ArrayList<>())
                    .add(statement);
        }
    }

    private void persistCopyEdges(long programSourceId, CobolSemanticModel model,
            Map<String, Long> copybookIdByFileName) {
        Set<Long> copybookIds = new TreeSet<>();
        for (CopyExpansionEntry entry : model.copyExpansions()) {
            Path copybook = Path.of(entry.copybookPath()).getFileName();
            Long copybookId = copybook == null ? null
                    : copybookIdByFileName.get(copybook.toString());
            if (copybookId != null) {
                copybookIds.add(copybookId);
            }
        }
        long edgeSeq = 0;
        for (long copybookId : copybookIds) {
            dao.insertCallEdge(new CallEdgeRecord(programSourceId * ID_STRIDE + (++edgeSeq),
                    copybookId, programSourceId, IncrementalAnalysisPlanner.COPY_EDGE_KIND,
                    null, null));
        }
    }

    private void analyzeJcl(List<ScanFile> targets, List<ScanFile> allFiles) {
        Map<String, Long> programIdByName = new HashMap<>();
        for (ScanFile file : allFiles) {
            if (file.kind() != SourceKind.COBOL) {
                continue;
            }
            long id = idByRel.get(file.relPath());
            CobolSemanticModel model = cobolModelsById.get(id);
            if (model != null) {
                programIdByName.put(model.programId(), id);
            } else {
                dao.findProgram(id).ifPresent(p -> programIdByName.put(p.programIdName(), id));
            }
        }
        for (ScanFile file : targets) {
            long id = idByRel.get(file.relPath());
            if (file.kind() != SourceKind.JCL || !decodedById.containsKey(id)) {
                continue;
            }
            ParseOutcome<JclJobModel> outcome = jclParser.parse(decodedById.get(id),
                    List.of(options.inputDir().resolve("jcl")));
            if (outcome instanceof ParseOutcome.Failure<JclJobModel> failure) {
                recordFinding(id, file.relPath(), failure.finding());
                upsertNode(new NodeRecord(id, SourceKind.JCL.nodeType, file.fileName()));
                continue;
            }
            JclJobModel job = outcome.value().orElseThrow();
            jclModelsById.put(id, job);
            upsertNode(new NodeRecord(id, SourceKind.JCL.nodeType, job.jobName()));
            Set<Long> executedPrograms = new TreeSet<>();
            for (JclStep step : job.steps()) {
                if (step.execKind() != JclExecKind.PGM) {
                    continue;
                }
                Long programSourceId = programIdByName.get(step.target());
                if (programSourceId != null) {
                    executedPrograms.add(programSourceId);
                }
            }
            long edgeSeq = 0;
            for (long programSourceId : executedPrograms) {
                dao.insertCallEdge(new CallEdgeRecord(id * ID_STRIDE + (++edgeSeq), id,
                        programSourceId, IncrementalAnalysisPlanner.EXECUTION_EDGE_KIND,
                        null, null));
            }
        }
    }

    private void analyzeBms(List<ScanFile> targets) {
        BmsSourceParser parser = new BmsSourceParser();
        for (ScanFile file : targets) {
            long id = idByRel.get(file.relPath());
            if (file.kind() != SourceKind.BMS || !decodedById.containsKey(id)) {
                continue;
            }
            BmsParseResult result = parser.parse(decodedById.get(id).text());
            for (BmsParseError error : result.errors()) {
                recordFinding(id, file.relPath(), Finding.parseFailure(
                        new SourcePosition(file.relPath(), Math.max(1, error.line()),
                                error.column() + 1, SourcePosition.UNKNOWN_BYTE_OFFSET),
                        error.message()));
            }
            List<BmsMapset> mapsets = BmsModelMapper.toEngineApi(result, file.relPath());
            bmsMapsetsById.put(id, mapsets);
            long mapsetSeq = 0;
            for (BmsMapset mapset : mapsets) {
                long mapsetId = id * ID_STRIDE + (++mapsetSeq);
                dao.insertBmsMapset(new BmsMapsetRecord(mapsetId, id, mapset.name()));
                long mapSeq = 0;
                for (BmsMap map : mapset.maps()) {
                    long mapId = mapsetId * 1000 + (++mapSeq);
                    dao.insertBmsMap(new BmsMapRecord(mapId, mapsetId, map.name(),
                            map.sizeRows(), map.sizeCols()));
                    long fieldSeq = 0;
                    for (BmsField field : map.fields()) {
                        dao.insertBmsField(new BmsFieldRecord(mapId * 1000 + (++fieldSeq), mapId,
                                field.name(), field.row(), field.column(), field.length(),
                                field.attributes()));
                    }
                }
            }
            String label = mapsets.isEmpty() ? file.fileName() : mapsets.get(0).name();
            upsertNode(new NodeRecord(id, SourceKind.BMS.nodeType, label));
        }
    }

    // ---- 呼出関係グラフ(M2) ----

    /**
     * グラフ構築は全ソースのモデルを要するため、増分scanで再解析対象にならなかったファイルも
     * ここでメモリ上に限りパースして補完する(SQLiteの各表は変更しない。復号・パースの失敗は
     * 前回scanでfindingとして記録済みのため、ここでは記録しない)。
     */
    private void ensureAllModels(List<ScanFile> files, Map<String, byte[]> bytesByRel) {
        BmsSourceParser bmsParser = new BmsSourceParser();
        for (ScanFile file : files) {
            long id = idByRel.get(file.relPath());
            boolean alreadyParsed = switch (file.kind()) {
                case COBOL -> cobolModelsById.containsKey(id);
                case JCL -> jclModelsById.containsKey(id);
                case BMS -> bmsMapsetsById.containsKey(id);
                case COPYBOOK -> true;
            };
            if (alreadyParsed) {
                continue;
            }
            DecodedSource decoded;
            try {
                decoded = decode(file, bytesByRel.get(file.relPath()));
            } catch (IllegalArgumentException e) {
                continue;
            }
            switch (file.kind()) {
                case COBOL -> {
                    ParseOutcome<CobolSemanticModel> outcome =
                            cobolParser.parse(decoded, options.copybookSearchPaths());
                    outcome.value().ifPresent(model -> {
                        cobolModelsById.put(id, model);
                        for (EmbeddedBlock block : model.embeddedBlocks()) {
                            if (block.kind() != EmbeddedBlockKind.SQL) {
                                continue;
                            }
                            sqlParser.parse(block).value().ifPresent(statement ->
                                    sqlModelsByProgramId.computeIfAbsent(model.programId(),
                                            k -> new ArrayList<>()).add(statement));
                        }
                    });
                }
                case JCL -> jclParser.parse(decoded,
                                List.of(options.inputDir().resolve("jcl"))).value()
                        .ifPresent(job -> jclModelsById.put(id, job));
                case BMS -> bmsMapsetsById.put(id, BmsModelMapper.toEngineApi(
                        bmsParser.parse(decoded.text()), file.relPath()));
                default -> {
                }
            }
        }
    }

    /**
     * 全モデルから呼出関係グラフを構築し、グラフ層(ID {@link #GRAPH_ID_BASE} 以上)を
     * 全消去のうえ NODE・CALL_EDGE・FINDING へ再投入する。プログラム・ジョブのノードは
     * NODE.id=SOURCE.id 規約の既存行を参照し、それ以外のノードはグラフのノードID昇順で
     * 決定論的に採番する。
     */
    private LinkResult linkAndPersistCallGraph() {
        List<BmsMapset> mapsets = new ArrayList<>();
        bmsMapsetsById.values().forEach(mapsets::addAll);
        LinkResult result = CallGraphLinker.link(new LinkerInput(
                cobolModelsById.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(Map.Entry::getValue).toList(),
                List.copyOf(jclModelsById.values()), mapsets, sqlModelsByProgramId,
                readTransactionTable()));

        dao.deleteFindingsIdAtLeast(GRAPH_ID_BASE);
        dao.deleteCallEdgesIdAtLeast(GRAPH_ID_BASE);
        dao.deleteNodesIdAtLeast(GRAPH_ID_BASE);

        Map<String, Long> numericByGraphNodeId =
                persistGraphNodes(result.graph().nodes(), sourceBackedNodeIds(result));
        persistGraphEdges(result, numericByGraphNodeId);
        persistLinkerFindings(result.findings());
        return result;
    }

    /**
     * ノード写像構築: NODE.id=SOURCE.id 規約の既存行を参照するプログラム・ジョブノードについて、
     * グラフノードID→ソースIDの写像を作る。プログラム名のキーはノードラベル(大文字化済み)と
     * 揃えるため大文字化する。
     */
    private Map<String, Long> sourceBackedNodeIds(LinkResult result) {
        Map<String, Long> programSourceIdByName = new HashMap<>();
        for (Map.Entry<Long, CobolSemanticModel> entry : cobolModelsById.entrySet()) {
            programSourceIdByName.put(
                    entry.getValue().programId().toUpperCase(Locale.ROOT), entry.getKey());
        }
        Map<String, Long> jobSourceIdByName = new HashMap<>();
        for (Map.Entry<Long, JclJobModel> entry : jclModelsById.entrySet()) {
            jobSourceIdByName.put(entry.getValue().jobName(), entry.getKey());
        }
        Map<String, Long> sourceBackedByGraphNodeId = new HashMap<>();
        for (CallGraphNode node : result.graph().nodes()) {
            Long sourceBacked = switch (node.kind()) {
                case PROGRAM -> programSourceIdByName.get(node.label());
                case JOB -> jobSourceIdByName.get(node.label());
                default -> null;
            };
            if (sourceBacked != null) {
                sourceBackedByGraphNodeId.put(node.id(), sourceBacked);
            }
        }
        return sourceBackedByGraphNodeId;
    }

    /**
     * ノード永続化: ソース非対応ノードへ {@link #GRAPH_ID_BASE} からの連番をノードID昇順で
     * 採番して保存し、全グラフノードの数値ID写像を返す。
     */
    private Map<String, Long> persistGraphNodes(List<CallGraphNode> nodes,
            Map<String, Long> sourceBackedByGraphNodeId) {
        Map<String, Long> numericByGraphNodeId = new HashMap<>();
        long nodeId = GRAPH_ID_BASE;
        for (CallGraphNode node : nodes) {
            Long sourceBacked = sourceBackedByGraphNodeId.get(node.id());
            if (sourceBacked != null) {
                numericByGraphNodeId.put(node.id(), sourceBacked);
            } else {
                numericByGraphNodeId.put(node.id(), nodeId);
                dao.insertNode(new NodeRecord(nodeId, node.kind().name(), node.label()));
                nodeId++;
            }
        }
        return numericByGraphNodeId;
    }

    /** 辺永続化: グラフの全辺を辺順の連番IDで保存する。動的CALL辺は指定変数名(辞書順)を持つ。 */
    private void persistGraphEdges(LinkResult result, Map<String, Long> numericByGraphNodeId) {
        long edgeId = GRAPH_ID_BASE;
        for (CallGraphEdge edge : result.graph().edges()) {
            Set<String> variables = result.dynamicCallVariables().get(edge);
            dao.insertCallEdge(new CallEdgeRecord(edgeId++,
                    numericByGraphNodeId.get(edge.fromId()), numericByGraphNodeId.get(edge.toId()),
                    edge.kind().name(), edge.resolution().name(),
                    variables == null ? null : String.join(",", variables)));
        }
    }

    /**
     * findings 永続化: linker 由来 finding を {@link #GRAPH_ID_BASE} からの連番IDで保存する。
     * 不変条件: scan 由来の FINDING の行ID(SOURCE.id×{@link #ID_STRIDE}+連番)は、
     * SOURCE.id が {@code GRAPH_ID_BASE / ID_STRIDE}(=1,000,000)未満である限り
     * GRAPH_ID_BASE と衝突しない。
     */
    private void persistLinkerFindings(List<Finding> findings) {
        Map<String, Long> sourceIdByModelFile = new HashMap<>();
        for (Map.Entry<Long, CobolSemanticModel> entry : cobolModelsById.entrySet()) {
            sourceIdByModelFile.put(entry.getValue().sourceFile(), entry.getKey());
        }
        Map<Long, String> relPathBySourceId = new HashMap<>();
        idByRel.forEach((rel, id) -> relPathBySourceId.put(id, rel));
        long findingId = GRAPH_ID_BASE;
        for (Finding finding : findings) {
            Long sourceId = sourceIdByModelFile.get(finding.location().file());
            if (sourceId == null) {
                System.err.println("警告: linker finding の対象ソースを特定できないため保存しない: "
                        + finding.location().file() + " (" + finding.ruleId() + ")");
                continue;
            }
            dao.insertFinding(new FindingRecord(findingId++, finding.ruleId(),
                    finding.level().name(), sourceId, finding.location().line(),
                    finding.location().column(), finding.location().byteOffset(),
                    finding.message(), sarifJson(relPathBySourceId.get(sourceId), finding)));
        }
    }

    /** トランザクション定義表のトランザクションID・プログラム名の値の妥当性検証(資産名の形式)。 */
    private static final java.util.regex.Pattern MEMBER_NAME_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9@#$-]{1,8}");

    /**
     * トランザクション定義表(INPUT_DIR/cics/*.csv。列はトランザクションIDとプログラム名)を
     * 読み込む。1行目は常にヘッダとして読み飛ばし、2行目以降のうち資産名の形式に合わない行は
     * 読み飛ばす。復号できないCSVは警告のうえファイル単位で読み飛ばし、残りの処理を継続する。
     */
    private Map<String, String> readTransactionTable() {
        Path dir = options.inputDir().resolve("cics");
        if (!Files.isDirectory(dir)) {
            return Map.of();
        }
        Map<String, String> table = new TreeMap<>();
        try (Stream<Path> children = Files.list(dir)) {
            List<Path> csvFiles = children.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".csv"))
                    .sorted().toList();
            for (Path csv : csvFiles) {
                List<String> lines;
                try {
                    lines = Files.readAllLines(csv, java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException e) {
                    System.err.println("警告: トランザクション定義表を復号できないため読み飛ばす: "
                            + csv + " (" + e + ")");
                    continue;
                }
                for (String line : lines.stream().skip(1).toList()) {
                    String[] fields = line.split(",");
                    if (fields.length != 2) {
                        continue;
                    }
                    String transId = fields[0].trim();
                    String program = fields[1].trim();
                    if (MEMBER_NAME_PATTERN.matcher(transId).matches()
                            && MEMBER_NAME_PATTERN.matcher(program).matches()) {
                        table.put(transId, program);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return table;
    }

    // ---- 共通処理 ----

    private void upsertNode(NodeRecord node) {
        if (dao.findNode(node.id()).isPresent()) {
            dao.updateNode(node);
        } else {
            dao.insertNode(node);
        }
    }

    private void recordFinding(long sourceId, String relPath, Finding finding) {
        runFindings.add(finding);
        int seq = findingSeqBySource.merge(sourceId, 1, Integer::sum);
        dao.insertFinding(new FindingRecord(sourceId * ID_STRIDE + seq, finding.ruleId(),
                finding.level().name(), sourceId, finding.location().line(),
                finding.location().column(), finding.location().byteOffset(), finding.message(),
                sarifJson(relPath, finding)));
    }

    private static String sarifJson(String relPath, Finding finding) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject()
                .name("ruleId").value(finding.ruleId())
                .name("level").value(finding.level().sarifName())
                .name("message").beginObject().name("text").value(finding.message()).endObject()
                .name("locations").beginArray().beginObject()
                .name("physicalLocation").beginObject()
                .name("artifactLocation").beginObject().name("uri").value(relPath).endObject()
                .name("region").beginObject()
                .name("startLine").value(finding.location().line())
                .name("startColumn").value(finding.location().column())
                .endObject()
                .endObject()
                .endObject().endArray()
                .endObject();
        return writer.toString();
    }
}
