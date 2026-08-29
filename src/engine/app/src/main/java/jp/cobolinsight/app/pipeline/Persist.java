package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.analysis.dataflow.CfgBuilder;
import jp.cobolinsight.analysis.linker.LinkResult;
import jp.cobolinsight.app.persistence.IncrementalAnalysisPlanner;
import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.BmsFieldRecord;
import jp.cobolinsight.app.persistence.model.BmsMapRecord;
import jp.cobolinsight.app.persistence.model.BmsMapsetRecord;
import jp.cobolinsight.app.persistence.model.CallEdgeRecord;
import jp.cobolinsight.app.persistence.model.EncodingInfoRecord;
import jp.cobolinsight.app.persistence.model.FindingRecord;
import jp.cobolinsight.app.persistence.model.NodeRecord;
import jp.cobolinsight.app.persistence.model.ParagraphEdgeRecord;
import jp.cobolinsight.app.persistence.model.ParagraphRecord;
import jp.cobolinsight.app.persistence.model.ProgramRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import jp.cobolinsight.app.persistence.model.SqlStmtRecord;
import jp.cobolinsight.core.bms.BmsField;
import jp.cobolinsight.core.bms.BmsMap;
import jp.cobolinsight.core.bms.BmsMapset;
import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.callgraph.CallGraphNode;
import jp.cobolinsight.core.callgraph.NodeKind;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.json.JsonWriter;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.GoToStatement;
import jp.cobolinsight.core.semantic.PerformRelation;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.CopyExpansionEntry;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Writes the run to SQLite. IDs are deterministic: SOURCE ids follow the lexicographic path order
 * and start at one, an existing database keeps the ids it already handed out, NODE.id equals
 * SOURCE.id, and a child row's id is derived as {@code SOURCE.id × 1,000,000 + sequence}.
 *
 * <p>Only sources whose content hash changed — or whose dependencies did — are rewritten. The rest
 * keep their rows, and their findings are counted from the database rather than inserted again.
 */
public final class Persist implements Step {

    /** Step between derived ids of child rows (PARAGRAPH, FINDING, SQL_STMT, CALL_EDGE, BMS…). */
    private static final long ID_STRIDE = 1_000_000L;

    /**
     * Lower bound for the call graph layer: nodes with no source of their own, graph edges, and
     * linker findings. The whole layer is wiped and rebuilt on every scan, so it has to sit clear
     * of the ids scan derives from SOURCE.id.
     *
     * <p>Invariant: SOURCE.id stays below {@code GRAPH_ID_BASE / ID_STRIDE} (= 1,000,000).
     * Above that, a child row's id would reach into the graph layer's range and be wiped with it.
     */
    public static final long GRAPH_ID_BASE = 1_000_000_000_000L;

    /** What the scan did, once the writing is over. */
    public record Outcome(List<String> analyzed, List<String> skipped, List<String> removed,
            int findingCount, boolean hasError, boolean hasWarning) {

        public Outcome {
            analyzed = List.copyOf(analyzed);
            skipped = List.copyOf(skipped);
            removed = List.copyOf(removed);
        }
    }

    private SourceSet set;
    private PersistenceDao dao;
    private final Map<String, Long> idByRel = new LinkedHashMap<>();
    private final Map<Long, Integer> findingSeqBySource = new HashMap<>();

    @Override
    public void apply(SourceSet s) {
        this.set = s;
        String root = Paths.rootOf(s.root());
        Map<String, String> hashByRel = new LinkedHashMap<>();
        for (SourceUnit unit : s.units()) {
            byte[] bytes = s.bytes().get(unit.relPath());
            // The code page in use belongs in the hash. Changing --codepage does not change the
            // bytes, so a hash of the bytes alone would never ask for the decoding to be redone.
            hashByRel.put(unit.relPath(),
                    Paths.sha256(bytes) + ":" + Decode.overrideOf(s, unit));
        }

        try (PersistenceDatabase database = PersistenceDatabase.open(s.options().databaseFile())) {
            dao = new PersistenceDao(database.connection());
            Map<String, SourceRecord> existingByPath = new LinkedHashMap<>();
            for (SourceRecord source : dao.findSourcesByRoot(root)) {
                existingByPath.put(source.path(), source);
            }
            Set<String> currentPaths = new LinkedHashSet<>();
            s.units().forEach(unit -> currentPaths.add(unit.relPath()));
            List<String> removed = existingByPath.keySet().stream()
                    .filter(path -> !currentPaths.contains(path)).toList();

            IncrementalAnalysisPlanner planner = new IncrementalAnalysisPlanner(dao);
            Set<Long> reanalysisIds = new LinkedHashSet<>();
            for (SourceUnit unit : s.units()) {
                SourceRecord existing = existingByPath.get(unit.relPath());
                if (existing != null) {
                    reanalysisIds.addAll(planner.determineReanalysisTargets(existing.id(),
                            hashByRel.get(unit.relPath())));
                }
            }
            // A source that vanished takes its dependants with it: a program whose copybook is gone
            // no longer matches the analysis on record.
            for (String path : removed) {
                reanalysisIds.addAll(planner.dependentsOf(existingByPath.get(path).id()));
            }

            // IDs are unique across every asset folder, so rows of one folder never meet another's.
            long maxId = dao.maxSourceId();
            List<SourceUnit> targets = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (SourceUnit unit : s.units()) {
                SourceRecord existing = existingByPath.get(unit.relPath());
                long id = existing != null ? existing.id() : ++maxId;
                idByRel.put(unit.relPath(), id);
                // A source with no NODE row has not been analysed, however well its hash matches.
                // `translate` registers SOURCE rows just to satisfy the line map's foreign key.
                if (existing == null || reanalysisIds.contains(id) || dao.findNode(id).isEmpty()) {
                    targets.add(unit);
                } else {
                    skipped.add(unit.relPath());
                }
            }

            dao.inTransaction(() -> {
                for (String path : removed) {
                    long id = existingByPath.get(path).id();
                    dao.deleteSourceCascade(id);
                    dao.deleteNode(id);
                }
                writeSources(root, targets, existingByPath, hashByRel);
                writeCopybookNodes(targets);
                writeCobol(targets);
                writeJcl(targets);
                writeBms(targets);
                writeCallGraph();
            });

            int persistedFindings = 0;
            boolean hasError = false;
            boolean hasWarning = false;
            for (String path : skipped) {
                for (FindingRecord finding : dao.findFindingsBySource(idByRel.get(path))) {
                    if (finding.id() >= GRAPH_ID_BASE) {
                        continue;
                    }
                    persistedFindings++;
                    hasError |= "ERROR".equals(finding.level());
                    hasWarning |= "WARNING".equals(finding.level());
                }
            }
            int freshFindings = 0;
            for (SourceUnit unit : targets) {
                freshFindings += findingsOf(unit).size();
            }
            s.artifact(Outcome.class, new Outcome(
                    targets.stream().map(SourceUnit::relPath).toList(), skipped, removed,
                    freshFindings + persistedFindings, hasError, hasWarning));
        }
    }

    /** Decode and parse failures recorded against one source, in the order they happened. */
    private List<Finding> findingsOf(SourceUnit unit) {
        List<Finding> all = new ArrayList<>(set.findingsOf(unit.relPath()));
        all.addAll(set.sqlParseFailuresByPath().getOrDefault(unit.relPath(), List.of()));
        return all;
    }

    // ---- SOURCE, ENCODING_INFO and the findings of the sources being rewritten ----

    private void writeSources(String root, List<SourceUnit> targets,
            Map<String, SourceRecord> existingByPath, Map<String, String> hashByRel) {
        for (SourceUnit unit : targets) {
            long id = idByRel.get(unit.relPath());
            if (existingByPath.containsKey(unit.relPath())) {
                dao.deleteSourceCascade(id);
                dao.deleteCallEdgesTo(id, IncrementalAnalysisPlanner.COPY_EDGE_KIND);
                dao.deleteCallEdgesFrom(id, IncrementalAnalysisPlanner.EXECUTION_EDGE_KIND);
            }
            byte[] bytes = set.bytes().get(unit.relPath());
            DecodedSource decoded = set.decoded().get(unit.relPath());
            String codepage = decoded == null ? null : decoded.encoding().detectedCharset();
            dao.insertSource(new SourceRecord(id, root, unit.relPath(), codepage,
                    hashByRel.get(unit.relPath()), bytes.length));
            if (decoded != null) {
                dao.insertEncodingInfo(new EncodingInfoRecord(id,
                        decoded.encoding().detectedCharset(), decoded.encoding().confidence(),
                        decoded.encoding().manualOverride(), decoded.encoding().soSiPresent()));
            }
            for (Finding finding : findingsOf(unit)) {
                writeFinding(id, unit.relPath(), finding);
            }
            if (decoded == null) {
                upsertNode(new NodeRecord(id, nodeTypeOf(unit.kind()), unit.fileName()));
            }
        }
    }

    private void writeCopybookNodes(List<SourceUnit> targets) {
        for (SourceUnit unit : targets) {
            if (unit.kind() == AssetKind.COPYBOOK && set.decoded().containsKey(unit.relPath())) {
                upsertNode(new NodeRecord(idByRel.get(unit.relPath()), nodeTypeOf(unit.kind()),
                        unit.fileName()));
            }
        }
    }

    // ---- PROGRAM, PARAGRAPH, PARAGRAPH_EDGE, SQL_STMT and COPY edges ----

    private void writeCobol(List<SourceUnit> targets) {
        // COPY resolution ignores case on Windows, so the file name lookup must too. The candidates
        // are whatever the walk called a copybook, not whatever carries a copybook extension.
        Map<String, Long> copybookIdByFileName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (SourceUnit unit : set.unitsOf(AssetKind.COPYBOOK)) {
            copybookIdByFileName.put(unit.fileName(), idByRel.get(unit.relPath()));
        }
        for (SourceUnit unit : targets) {
            if (unit.kind() != AssetKind.COBOL || !set.decoded().containsKey(unit.relPath())) {
                continue;
            }
            long id = idByRel.get(unit.relPath());
            CobolSemanticModel model = set.programsByPath().get(unit.relPath());
            if (model == null) {
                upsertNode(new NodeRecord(id, nodeTypeOf(AssetKind.COBOL), unit.fileName()));
                continue;
            }
            upsertNode(new NodeRecord(id, nodeTypeOf(AssetKind.COBOL), model.programId()));
            dao.insertProgram(new ProgramRecord(id, id, model.programId()));
            long paragraphSeq = 0;
            List<Long> paragraphIds = new ArrayList<>();
            Map<String, Long> paragraphIdByName = new LinkedHashMap<>();
            for (Procedure procedure : model.procedures()) {
                long paragraphId = id * ID_STRIDE + (++paragraphSeq);
                dao.insertParagraph(new ParagraphRecord(paragraphId, id, procedure.name(),
                        procedure.range().start().line(), procedure.range().end().line()));
                paragraphIds.add(paragraphId);
                // Where two paragraphs share a name, the first one defined is the target, which is
                // how the control flow graph resolves it too.
                paragraphIdByName.putIfAbsent(procedure.name().toUpperCase(Locale.ROOT),
                        paragraphId);
            }
            writeParagraphEdges(id, model, paragraphIds, paragraphIdByName);
            writeSqlStatements(id, unit.relPath());
            writeCopyEdges(id, model, copybookIdByFileName);
        }
    }

    /** One flow between paragraphs. {@code line} is the PERFORM or GO TO line, null for fall-through. */
    private record ParagraphFlow(String kind, String toName, Integer line) {
    }

    /**
     * Writes the flow between paragraphs. Paragraphs are taken in definition order; within one,
     * its PERFORM and GO TO statements in line order, then the fall-through into the next
     * paragraph. {@code PERFORM … THRU} contributes a single edge to the entry paragraph — the
     * fall-through edges already lead from there to the THRU target. An edge whose target name
     * matches no paragraph keeps the name and leaves the id empty.
     *
     * <p>A PERFORM belongs to the paragraph whose range holds it. Matching on the paragraph name
     * instead would attach a PERFORM to every paragraph sharing that name.
     */
    private void writeParagraphEdges(long sourceId, CobolSemanticModel model,
            List<Long> paragraphIds, Map<String, Long> paragraphIdByName) {
        List<Procedure> procedures = model.procedures();
        long edgeId = 0;
        for (int i = 0; i < procedures.size(); i++) {
            Procedure procedure = procedures.get(i);
            Set<SourceRange> performRanges = new LinkedHashSet<>();
            collectPerformRanges(procedure.statements(), performRanges);
            List<ParagraphFlow> flows = new ArrayList<>();
            for (PerformRelation perform : model.performs()) {
                if (performRanges.contains(perform.range())) {
                    flows.add(new ParagraphFlow("PERFORM", perform.targetProcedure(),
                            perform.range().start().line()));
                }
            }
            collectGoTo(procedure.statements(), flows);
            flows.sort((a, b) -> Integer.compare(a.line(), b.line()));
            if (i + 1 < procedures.size() && fallsThrough(procedure)) {
                flows.add(new ParagraphFlow("FALLTHROUGH", procedures.get(i + 1).name(), null));
            }
            int seq = 0;
            for (ParagraphFlow flow : flows) {
                dao.insertParagraphEdge(new ParagraphEdgeRecord(sourceId * ID_STRIDE + (++edgeId),
                        sourceId, paragraphIds.get(i),
                        paragraphIdByName.get(flow.toName().toUpperCase(Locale.ROOT)),
                        flow.toName(), flow.kind(), flow.line(), ++seq));
            }
        }
    }

    /**
     * Whether the end of a paragraph falls into the next one. A paragraph ending in an
     * unconditional GO TO, STOP RUN, GOBACK or EXIT PROGRAM does not, because control has already
     * gone elsewhere. The test matches {@link CfgBuilder}'s.
     */
    private static boolean fallsThrough(Procedure procedure) {
        List<Statement> statements = procedure.statements();
        if (statements.isEmpty()) {
            return true;
        }
        Statement last = statements.get(statements.size() - 1);
        if (last instanceof GoToStatement goTo) {
            // GO TO … DEPENDING ON falls through when no target matches.
            return goTo.targets().size() > 1 || goTo.dependingOn().isPresent();
        }
        return !(last instanceof SimpleStatement simple && CfgBuilder.isTerminator(simple));
    }

    /** Collects PERFORM ranges, including those nested inside IF, EVALUATE and the like. */
    private static void collectPerformRanges(List<Statement> statements, Set<SourceRange> ranges) {
        for (Statement statement : statements) {
            if (statement instanceof CompoundStatement compound) {
                for (StatementBlock block : compound.blocks()) {
                    collectPerformRanges(block.statements(), ranges);
                }
            } else if (statement instanceof SimpleStatement simple
                    && "PERFORM".equalsIgnoreCase(simple.verb())) {
                ranges.add(simple.range());
            }
        }
    }

    /** Collects GO TO statements in source order, including those nested inside compound ones. */
    private static void collectGoTo(List<Statement> statements, List<ParagraphFlow> flows) {
        for (Statement statement : statements) {
            if (statement instanceof CompoundStatement compound) {
                for (StatementBlock block : compound.blocks()) {
                    collectGoTo(block.statements(), flows);
                }
            } else if (statement instanceof GoToStatement goTo) {
                for (String target : goTo.targets()) {
                    flows.add(new ParagraphFlow("GOTO", target, goTo.range().start().line()));
                }
            }
        }
    }

    private void writeSqlStatements(long sourceId, String relPath) {
        long sqlSeq = 0;
        for (SqlStatementModel statement : set.sqlByPath().getOrDefault(relPath, List.of())) {
            dao.insertSqlStmt(new SqlStmtRecord(sourceId * ID_STRIDE + (++sqlSeq), sourceId,
                    statement.kind().name(), statement.mangledText(), statement.originalText()));
        }
    }

    private void writeCopyEdges(long programSourceId, CobolSemanticModel model,
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

    // ---- JCL and BMS ----

    private void writeJcl(List<SourceUnit> targets) {
        Map<String, Long> programIdByName = new HashMap<>();
        for (SourceUnit unit : set.unitsOf(AssetKind.COBOL)) {
            long id = idByRel.get(unit.relPath());
            CobolSemanticModel model = set.programsByPath().get(unit.relPath());
            if (model != null) {
                programIdByName.put(model.programId(), id);
            } else {
                dao.findProgram(id).ifPresent(p -> programIdByName.put(p.programIdName(), id));
            }
        }
        for (SourceUnit unit : targets) {
            if (unit.kind() != AssetKind.JCL || !set.decoded().containsKey(unit.relPath())) {
                continue;
            }
            long id = idByRel.get(unit.relPath());
            JclJobModel job = set.jobsByPath().get(unit.relPath());
            if (job == null) {
                upsertNode(new NodeRecord(id, nodeTypeOf(AssetKind.JCL), unit.fileName()));
                continue;
            }
            upsertNode(new NodeRecord(id, nodeTypeOf(AssetKind.JCL), job.jobName()));
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

    private void writeBms(List<SourceUnit> targets) {
        for (SourceUnit unit : targets) {
            if (unit.kind() != AssetKind.BMS || !set.decoded().containsKey(unit.relPath())) {
                continue;
            }
            long id = idByRel.get(unit.relPath());
            List<BmsMapset> mapsets = set.mapsetsByPath().getOrDefault(unit.relPath(), List.of());
            long mapsetSeq = 0;
            for (BmsMapset mapset : mapsets) {
                long mapsetId = id * ID_STRIDE + (++mapsetSeq);
                dao.insertBmsMapset(new BmsMapsetRecord(mapsetId, id, mapset.name()));
                long mapSeq = 0;
                for (BmsMap map : mapset.maps()) {
                    // Map and field ids come from the parent id with a stride of 1,000. This holds
                    // as long as one mapset has at most 999 maps and one map at most 999 fields.
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
            String label = mapsets.isEmpty() ? unit.fileName() : mapsets.get(0).name();
            upsertNode(new NodeRecord(id, nodeTypeOf(AssetKind.BMS), label));
        }
    }

    // ---- the call graph layer ----

    /**
     * Wipes the graph layer (ids at or above {@link #GRAPH_ID_BASE}) and writes it again. Program
     * and job nodes reuse the NODE row whose id equals their SOURCE.id; every other node is
     * numbered from the base in graph node order, which keeps the numbering deterministic.
     */
    private void writeCallGraph() {
        LinkResult result = set.artifact(LinkResult.class).orElse(null);
        if (result == null) {
            return;
        }
        dao.deleteFindingsIdAtLeast(GRAPH_ID_BASE);
        dao.deleteCallEdgesIdAtLeast(GRAPH_ID_BASE);
        dao.deleteNodesIdAtLeast(GRAPH_ID_BASE);

        Map<String, Long> numericByGraphNodeId =
                writeGraphNodes(result.graph().nodes(), sourceBackedNodeIds(result));
        writeGraphEdges(result, numericByGraphNodeId);
        writeLinkerFindings(result.findings());
    }

    /** Graph node id to SOURCE.id, for the nodes that reuse a NODE row the scan already wrote. */
    private Map<String, Long> sourceBackedNodeIds(LinkResult result) {
        Map<String, Long> programSourceIdByName = new HashMap<>();
        set.programsByPath().forEach((relPath, model) -> programSourceIdByName
                .put(model.programId().toUpperCase(Locale.ROOT), idByRel.get(relPath)));
        Map<String, Long> jobSourceIdByName = new HashMap<>();
        set.jobsByPath().forEach((relPath, job) ->
                jobSourceIdByName.put(job.jobName(), idByRel.get(relPath)));
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
     * Numbers the nodes with no source of their own from {@link #GRAPH_ID_BASE} upward, and returns
     * the numeric id of every graph node.
     *
     * <p>An unanalysable asset keeps its own NODE row and only changes type: leaving the type the
     * walk gave it (PROGRAM, say) would make it indistinguishable from an asset that was read.
     */
    private Map<String, Long> writeGraphNodes(List<CallGraphNode> nodes,
            Map<String, Long> sourceBackedByGraphNodeId) {
        Map<String, Long> numericByGraphNodeId = new HashMap<>();
        long nodeId = GRAPH_ID_BASE;
        for (CallGraphNode node : nodes) {
            Long sourceBacked = sourceBackedByGraphNodeId.get(node.id());
            if (sourceBacked != null) {
                numericByGraphNodeId.put(node.id(), sourceBacked);
                if (node.kind() == NodeKind.UNANALYZABLE) {
                    upsertNode(new NodeRecord(sourceBacked, node.kind().name(), node.label()));
                }
            } else {
                numericByGraphNodeId.put(node.id(), nodeId);
                dao.insertNode(new NodeRecord(nodeId, node.kind().name(), node.label()));
                nodeId++;
            }
        }
        return numericByGraphNodeId;
    }

    /**
     * Writes every edge with an id running in edge order. A dynamic CALL edge carries the names of
     * the variables that name its target, in lexicographic order. Row order follows the edges; the
     * order of the original (JCL step order, statement order) is what {@code seq} carries.
     */
    private void writeGraphEdges(LinkResult result, Map<String, Long> numericByGraphNodeId) {
        long edgeId = GRAPH_ID_BASE;
        for (CallGraphEdge edge : result.graph().edges()) {
            Set<String> variables = result.dynamicCallVariables().get(edge);
            dao.insertCallEdge(new CallEdgeRecord(edgeId++,
                    numericByGraphNodeId.get(edge.fromId()), numericByGraphNodeId.get(edge.toId()),
                    edge.kind().name(), edge.resolution().name(),
                    variables == null ? null : String.join(",", variables),
                    edge.seq(), edge.line()));
        }
    }

    /**
     * Writes the linker's findings — its record of how each call was resolved — with ids running
     * from {@link #GRAPH_ID_BASE}. Invariant: a scan finding's id ({@code SOURCE.id × ID_STRIDE +
     * sequence}) stays clear of that base while SOURCE.id is below 1,000,000.
     */
    private void writeLinkerFindings(List<Finding> findings) {
        Map<String, Long> sourceIdByModelFile = new HashMap<>();
        set.programsByPath().forEach((relPath, model) ->
                sourceIdByModelFile.put(model.sourceFile(), idByRel.get(relPath)));
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

    // ---- shared ----

    /** NODE.type for an asset kind. The graph layer overwrites it where an asset is unanalysable. */
    private static String nodeTypeOf(AssetKind kind) {
        return switch (kind) {
            case BMS -> "BMS";
            case COBOL -> "PROGRAM";
            case COPYBOOK -> "COPYBOOK";
            case JCL -> "JCL";
        };
    }

    private void upsertNode(NodeRecord node) {
        if (dao.findNode(node.id()).isPresent()) {
            dao.updateNode(node);
        } else {
            dao.insertNode(node);
        }
    }

    private void writeFinding(long sourceId, String relPath, Finding finding) {
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
