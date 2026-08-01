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
import jp.cobolinsight.engineapi.source.AssetKind;
import jp.cobolinsight.engineapi.source.CopyExpansionEntry;
import jp.cobolinsight.engineapi.source.CopyInlineExpansion;
import jp.cobolinsight.engineapi.source.DecodedSource;
import jp.cobolinsight.engineapi.source.ExpandedCopyLine;
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
import jp.cobolinsight.engineapi.callgraph.NodeKind;
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
    /** 解析不能ノードのID接頭辞。相対パスと組んで他種別のノードIDと衝突しない値になる。 */
    private static final String UNANALYZABLE_NODE_ID_PREFIX = "unanalyzable:";

    public record Options(Path inputDir, Path databaseFile, List<Path> copybookSearchPaths,
            Map<String, String> codepageOverrides) {
    }

    /** 拡張子と内容が食い違った1件。種別名は {@link AssetKind} の名前をそのまま出す。 */
    public record KindMismatch(String path, String byExtension, String byContent) {
    }

    public record Summary(List<String> analyzed, List<String> skipped, List<String> removed,
            int findingCount, int exitCode, boolean truncated,
            List<String> undecided, List<KindMismatch> mismatches, List<String> unreadable) {

        /** 走査の付帯情報を持たない生成。取りこぼしも食い違いも無かった場合と同じ値になる。 */
        public Summary(List<String> analyzed, List<String> skipped, List<String> removed,
                int findingCount, int exitCode) {
            this(analyzed, skipped, removed, findingCount, exitCode, false,
                    List.of(), List.of(), List.of());
        }

        /**
         * 保存先のSQLiteプロジェクトファイルを添えたサマリJSON。GUIはこの値から資産一覧を読む。
         * copyExpansionFile はコピー句展開の成果物の書き出し先で、書き出していない場合は null を
         * 渡す(キー自体を出さない)。undecided 以降は走査の付帯情報で、GUI が「黙って落としたもの」
         * 「黙って解釈を変えたもの」「打ち切り」を利用者へ伝えるために使う。
         *
         * <p>undecided・mismatches・unreadable はいずれも<b>全件</b>を出し、件数を別に持たない
         * (配列の長さが件数である)。件数と例示を別々に持つと、engine 側で例示を打ち切る誘惑が残る。
         */
        public String toJson(String databaseFile, String copyExpansionFile) {
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writeArray(writer, "analyzed", analyzed);
            writeArray(writer, "skipped", skipped);
            writeArray(writer, "removed", removed);
            writer.name("findingCount").value(findingCount);
            writer.name("dbFile").value(databaseFile);
            if (copyExpansionFile != null) {
                writer.name("copyExpansionFile").value(copyExpansionFile);
            }
            if (truncated) {
                writer.name("truncated").value(true);
            }
            writeArray(writer, "undecided", undecided);
            writer.name("mismatches").beginArray();
            for (KindMismatch mismatch : mismatches) {
                writer.beginObject()
                        .name("path").value(mismatch.path())
                        .name("byExtension").value(mismatch.byExtension())
                        .name("byContent").value(mismatch.byContent())
                        .endObject();
            }
            writer.endArray();
            writeArray(writer, "unreadable", unreadable);
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

    /**
     * コピー句のインライン展開の成果物。プログラムごとに、原本の COPY 文の位置へ差し込まれる
     * コピー句の各行(コピー句内の由来行と REPLACING 適用後のテキスト)を持つ。原本へコピー句を
     * 展開した姿を示す用途に用いる。
     */
    public record CopyExpansions(List<ProgramExpansion> programs) {

        public CopyExpansions {
            programs = List.copyOf(programs);
        }

        /** 1プログラム分の展開。relPath は資産フォルダからの相対パス。 */
        public record ProgramExpansion(String relPath, String programId,
                List<CopyInlineExpansion> expansions) {

            public ProgramExpansion {
                expansions = List.copyOf(expansions);
            }
        }

        public String toJson() {
            JsonWriter writer = new JsonWriter();
            writer.beginObject().name("programs").beginArray();
            for (ProgramExpansion program : programs) {
                writer.beginObject()
                        .name("path").value(program.relPath())
                        .name("programId").value(program.programId())
                        .name("expansions").beginArray();
                for (CopyInlineExpansion expansion : program.expansions()) {
                    writer.beginObject()
                            .name("copyStatementLine").value(expansion.copyStatementLine())
                            .name("copybookName").value(expansion.copybookName())
                            .name("copybookPath").value(expansion.copybookPath())
                            .name("lines").beginArray();
                    for (ExpandedCopyLine line : expansion.lines()) {
                        writer.beginObject()
                                .name("copybookLine").value(line.copybookLine())
                                .name("text").value(line.text())
                                .endObject();
                    }
                    writer.endArray().endObject();
                }
                writer.endArray().endObject();
            }
            writer.endArray().endObject();
            return writer.toString();
        }
    }

    /**
     * scan の全結果。呼出関係グラフ・linker 由来の findings(解決根拠の記録)・コピー句の
     * インライン展開を含む。
     */
    public record Result(Summary summary, CallGraph callGraph, List<Finding> linkerFindings,
            CopyExpansions copyExpansions) {

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
    /** 復号・パースに失敗した資産の相対パス→失敗理由。呼出関係グラフの孤立ノードの材料。 */
    private final Map<String, String> unanalyzableReasonByRel = new TreeMap<>();
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
        SourceDiscovery.Result discovery = SourceDiscovery.discover(options.inputDir());
        List<ScanFile> files = new ArrayList<>();
        List<String> unreadable = new ArrayList<>(discovery.unreadable());
        Map<String, byte[]> bytesByRel = new LinkedHashMap<>();
        Map<String, String> hashByRel = new LinkedHashMap<>();
        for (ScanFile file : toScanFiles(discovery)) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file.absPath());
            } catch (IOException e) {
                // 読み取れない1ファイルで解析全体を止めない。対象から外し、件数を利用者へ伝える。
                System.err.println("警告: 読み取れないため対象から外す: " + file.absPath()
                        + " (" + e + ")");
                unreadable.add(file.relPath());
                continue;
            }
            files.add(file);
            bytesByRel.put(file.relPath(), bytes);
            // 適用するコードページをハッシュへ含める。--codepage の変更は原本のバイト列を
            // 変えないため、バイト列だけのハッシュでは復号のやり直しを促せない。
            hashByRel.put(file.relPath(),
                    sha256(bytes) + ":" + String.valueOf(codepageOverrideOf(file)));
        }

        String root = rootOf(options.inputDir());
        try (PersistenceDatabase database = PersistenceDatabase.open(options.databaseFile())) {
            dao = new PersistenceDao(database.connection());
            Map<String, SourceRecord> existingByPath = new LinkedHashMap<>();
            for (SourceRecord source : dao.findSourcesByRoot(root)) {
                existingByPath.put(source.path(), source);
            }
            Set<String> currentPaths = new LinkedHashSet<>();
            files.forEach(file -> currentPaths.add(file.relPath()));
            List<String> removed = existingByPath.keySet().stream()
                    .filter(path -> !currentPaths.contains(path)).toList();

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
            // 資産フォルダから消えたソースの依存元も解析し直す。取り込んでいたコピー句が消えれば、
            // 取込プログラムの解析結果は現物と合わなくなる。
            for (String path : removed) {
                reanalysisIds.addAll(planner.dependentsOf(existingByPath.get(path).id()));
            }

            // ID は全資産フォルダを通じて一意にする。別の資産フォルダの行と衝突させない。
            long maxId = dao.maxSourceId();
            List<ScanFile> targets = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (ScanFile file : files) {
                SourceRecord existing = existingByPath.get(file.relPath());
                long id = existing != null ? existing.id() : ++maxId;
                idByRel.put(file.relPath(), id);
                // NODE 行が無いソースは、内容ハッシュが一致しても解析済みではない。translate が
                // 行対応表の外部キーを満たすために登録した SOURCE 行がこれにあたる。
                if (existing == null || reanalysisIds.contains(id) || dao.findNode(id).isEmpty()) {
                    targets.add(file);
                } else {
                    skipped.add(file.relPath());
                }
            }

            dao.inTransaction(() -> {
                for (String path : removed) {
                    long id = existingByPath.get(path).id();
                    dao.deleteSourceCascade(id);
                    dao.deleteNode(id);
                }
                replaceSources(root, targets, existingByPath, bytesByRel, hashByRel);
                registerCopybookNodes(targets);
                analyzeCobol(targets, files);
                analyzeJcl(targets, files);
                analyzeBms(targets);
                ensureAllModels(files, bytesByRel);
                linkResult = linkAndPersistCallGraph(discovery.transactionTables());
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
                    runFindings.size() + persistedFindings, exitCode,
                    discovery.truncated(), discovery.undecided(),
                    discovery.mismatches().stream()
                            .map(m -> new KindMismatch(m.relPath(), m.byExtension().name(),
                                    m.byContent().name()))
                            .toList(),
                    unreadable);
            return new Result(summary, linkResult.graph(), linkResult.findings(),
                    collectCopyExpansions());
        }
    }

    // ---- 走査 ----

    /** 走査結果を scan の内部表現へ写す。種別の対応は {@link SourceKind} が持つ NODE.type を伴う。 */
    private static List<ScanFile> toScanFiles(SourceDiscovery.Result discovery) {
        List<ScanFile> files = new ArrayList<>();
        for (SourceDiscovery.DiscoveredFile file : discovery.files()) {
            files.add(new ScanFile(file.relPath(), file.absPath(), toSourceKind(file.kind())));
        }
        return files;
    }

    private static SourceKind toSourceKind(AssetKind kind) {
        return switch (kind) {
            case BMS -> SourceKind.BMS;
            case COBOL -> SourceKind.COBOL;
            case COPYBOOK -> SourceKind.COPYBOOK;
            case JCL -> SourceKind.JCL;
        };
    }

    /**
     * JCL の INCLUDE メンバを探す位置。従来構成では INPUT_DIR/jcl と一致するが、再帰探索で
     * 拾った JCL はその位置に無いため、当該ファイルの親ディレクトリを使う。
     */
    private static List<Path> jclSearchPaths(ScanFile file) {
        Path parent = file.absPath().getParent();
        return parent == null ? List.of() : List.of(parent);
    }

    /** 資産フォルダの識別子。同じフォルダを別表記で指しても同じ値になるよう絶対化・正規化する。 */
    static String rootOf(Path inputDir) {
        return inputDir.toAbsolutePath().normalize().toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- SOURCE・ENCODING_INFO の入替と復号 ----

    private void replaceSources(String root, List<ScanFile> targets,
            Map<String, SourceRecord> existingByPath,
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
            dao.insertSource(new SourceRecord(id, root, file.relPath(), codepage,
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

    /** 当該ファイルへ適用するコードページ手動指定。相対パス優先、無ければファイル名で引く。 */
    private String codepageOverrideOf(ScanFile file) {
        String override = options.codepageOverrides().get(file.relPath());
        return override != null ? override : options.codepageOverrides().get(file.fileName());
    }

    private DecodedSource decode(ScanFile file, byte[] bytes) {
        String override = codepageOverrideOf(file);
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

    private void analyzeCobol(List<ScanFile> targets, List<ScanFile> allFiles) {
        // Windowsではコピー句解決が探索名の大小を区別しないため、ファイル名照合も大小無視とする。
        // 対象は走査が COPYBOOK と決めたソースであり、拡張子で選び直さない(内容で COPYBOOK と
        // 決まったファイルを落とさないため)。
        Map<String, Long> copybookIdByFileName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ScanFile file : allFiles) {
            if (file.kind() == SourceKind.COPYBOOK) {
                copybookIdByFileName.put(file.fileName(), idByRel.get(file.relPath()));
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
                    jclSearchPaths(file));
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
                // BmsParseError の桁は0起点、SourcePosition の桁は1起点のため1加える。
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
                    // マップ・フィールドの行IDは親IDへ1,000刻みで連番を足して導出する。
                    // 前提: 1マップセットあたりのマップ、1マップあたりのフィールドはいずれも999以内。
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

    // ---- コピー句のインライン展開 ----

    /**
     * 全 COBOL の COPY 文インライン展開を相対パス昇順で集める。コピー句のパスは資産フォルダ配下に
     * あれば相対パスへ直し、外にあれば絶対パスのまま残す。展開の無いプログラムは載せない。
     */
    private CopyExpansions collectCopyExpansions() {
        Map<Long, String> relPathById = new TreeMap<>();
        idByRel.forEach((rel, id) -> relPathById.put(id, rel));
        List<CopyExpansions.ProgramExpansion> programs = new ArrayList<>();
        for (Map.Entry<Long, CobolSemanticModel> entry
                : new TreeMap<>(cobolModelsById).entrySet()) {
            CobolSemanticModel model = entry.getValue();
            if (model.copyInlineExpansions().isEmpty()) {
                continue;
            }
            List<CopyInlineExpansion> expansions = model.copyInlineExpansions().stream()
                    .map(expansion -> new CopyInlineExpansion(expansion.copyStatementLine(),
                            expansion.copybookName(),
                            relativize(options.inputDir(), Path.of(expansion.copybookPath())),
                            expansion.lines()))
                    .toList();
            programs.add(new CopyExpansions.ProgramExpansion(relPathById.get(entry.getKey()),
                    model.programId(), expansions));
        }
        programs.sort(java.util.Comparator.comparing(
                CopyExpansions.ProgramExpansion::relPath));
        return new CopyExpansions(programs);
    }

    /** 資産フォルダ配下なら相対パス(区切りは '/')、外なら絶対パスのまま。 */
    private static String relativize(Path inputDir, Path file) {
        Path base = inputDir.toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        return abs.startsWith(base) ? base.relativize(abs).toString().replace('\\', '/')
                : abs.toString().replace('\\', '/');
    }

    // ---- 呼出関係グラフ ----

    /**
     * グラフ構築は全ソースのモデルを要するため、増分scanで再解析対象にならなかったファイルも
     * ここでメモリ上に限りパースして補完する(SQLiteの各表は変更しない。復号・パースの失敗は
     * 前回scanでfindingとして記録済みのため、ここでは記録しない)。
     *
     * <p>ここは意味モデルを持たない資産が全て通る唯一の地点であり、復号・パースの失敗理由を
     * {@link #unanalyzableReasonByRel} へ集める。この理由はグラフの孤立ノードの属性になる。
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
                unanalyzableReasonByRel.put(file.relPath(), "復号に失敗した: " + e.getMessage());
                continue;
            }
            switch (file.kind()) {
                case COBOL -> {
                    ParseOutcome<CobolSemanticModel> outcome =
                            cobolParser.parse(decoded, options.copybookSearchPaths());
                    if (outcome instanceof ParseOutcome.Failure<CobolSemanticModel> failure) {
                        unanalyzableReasonByRel.put(file.relPath(),
                                failure.finding().message());
                    } else {
                        CobolSemanticModel model = outcome.value().orElseThrow();
                        cobolModelsById.put(id, model);
                        for (EmbeddedBlock block : model.embeddedBlocks()) {
                            if (block.kind() != EmbeddedBlockKind.SQL) {
                                continue;
                            }
                            sqlParser.parse(block).value().ifPresent(statement ->
                                    sqlModelsByProgramId.computeIfAbsent(model.programId(),
                                            k -> new ArrayList<>()).add(statement));
                        }
                    }
                }
                case JCL -> {
                    ParseOutcome<JclJobModel> outcome = jclParser.parse(decoded,
                            jclSearchPaths(file));
                    if (outcome instanceof ParseOutcome.Failure<JclJobModel> failure) {
                        unanalyzableReasonByRel.put(file.relPath(), failure.finding().message());
                    } else {
                        jclModelsById.put(id, outcome.value().orElseThrow());
                    }
                }
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
    private LinkResult linkAndPersistCallGraph(List<Path> transactionTables) {
        List<BmsMapset> mapsets = new ArrayList<>();
        bmsMapsetsById.values().forEach(mapsets::addAll);
        LinkResult linked = CallGraphLinker.link(new LinkerInput(
                cobolModelsById.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(Map.Entry::getValue).toList(),
                List.copyOf(jclModelsById.values()), mapsets, sqlModelsByProgramId,
                readTransactionTable(transactionTables)));
        LinkResult result = new LinkResult(withUnanalyzableNodes(linked.graph()),
                linked.findings(), linked.dynamicCallVariables());

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
     * 解析不能な資産を孤立ノードとして足したグラフ。呼出関係を読み取れない資産を図から落とすと
     * 図が資産の全体を表すと誤読されるため、辺を持たないノードとして残す。
     */
    private CallGraph withUnanalyzableNodes(CallGraph graph) {
        if (unanalyzableReasonByRel.isEmpty()) {
            return graph;
        }
        List<CallGraphNode> nodes = new ArrayList<>(graph.nodes());
        for (Map.Entry<String, String> entry : unanalyzableReasonByRel.entrySet()) {
            String relPath = entry.getKey();
            nodes.add(new CallGraphNode(UNANALYZABLE_NODE_ID_PREFIX + relPath,
                    NodeKind.UNANALYZABLE, relPath.substring(relPath.lastIndexOf('/') + 1),
                    Map.of("path", relPath, "reason", entry.getValue())));
        }
        return new CallGraph(nodes, graph.edges());
    }

    /**
     * ノード対応の構築: NODE.id=SOURCE.id 規約の既存行を参照するプログラム・ジョブ・解析不能
     * ノードについて、グラフノードID→ソースIDの対応表を作る。プログラム名のキーはノードラベル
     * (大文字化済み)と揃えるため大文字化する。
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
                case UNANALYZABLE -> idByRel.get(node.attributes().get("path"));
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
     * 採番して保存し、全グラフノードの数値IDの対応表を返す。
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

    /**
     * トランザクション定義表(列はトランザクションIDとプログラム名)を読み込む。対象は走査が
     * 表とみなした CSV であり、置き場所は問わない。1行目は常にヘッダとして読み飛ばし、2行目
     * 以降のうち資産名の形式({@link SourceDiscovery#MEMBER_NAME_PATTERN})に合わない行は
     * 読み飛ばす。復号できないCSVはファイル単位で読み飛ばし、残りの処理を継続する。
     */
    private static Map<String, String> readTransactionTable(List<Path> transactionTables) {
        Map<String, String> table = new TreeMap<>();
        for (Path csv : transactionTables) {
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
                if (SourceDiscovery.MEMBER_NAME_PATTERN.matcher(transId).matches()
                        && SourceDiscovery.MEMBER_NAME_PATTERN.matcher(program).matches()) {
                    table.put(transId, program);
                }
            }
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
