package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.analysis.dataflow.CfgBuilder;
import jp.cobolinsight.analysis.linker.CallGraphLinker;
import jp.cobolinsight.analysis.linker.LinkResult;
import jp.cobolinsight.app.persistence.IncrementalAnalysisPlanner;
import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.CallEdgeRecord;
import jp.cobolinsight.app.persistence.model.FindingRecord;
import jp.cobolinsight.app.persistence.model.JclDdRecord;
import jp.cobolinsight.app.persistence.model.JclStepRecord;
import jp.cobolinsight.app.persistence.model.NodeRecord;
import jp.cobolinsight.app.persistence.model.ParagraphEdgeRecord;
import jp.cobolinsight.app.persistence.model.ParagraphRecord;
import jp.cobolinsight.app.persistence.model.ProgramRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import jp.cobolinsight.app.persistence.model.SqlColumnUseRecord;
import jp.cobolinsight.app.persistence.model.SqlStatementRecord;
import jp.cobolinsight.app.persistence.model.SqlTableUseRecord;
import jp.cobolinsight.core.bms.BmsMapset;
import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.callgraph.CallGraphNode;
import jp.cobolinsight.core.callgraph.NodeKind;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.jcl.JclDataset;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclDisposition;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.jcl.JclUtilityFacts;
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
import jp.cobolinsight.core.sql.SqlColumnRef;
import jp.cobolinsight.core.sql.SqlDeclaredColumn;
import jp.cobolinsight.core.sql.SqlSetPair;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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

    /**
     * Step between derived ids of child rows (PARAGRAPH, FINDING, CALL_EDGE, JCL_STEP, JCL_DD,
     * SQL_STMT and the two SQL use tables).
     *
     * <p>Every one of those ids is {@code SOURCE.id × ID_STRIDE + sequence}, and the sequences of
     * one source overlap freely: each table numbers its own primary key, so a paragraph, a finding,
     * a JCL step and an SQL statement of the same source may all be id 1,000,001 without ever
     * meeting. What the stride keeps apart is the rows of different sources within one table, and
     * that holds as long as no source has more than a million rows in any single table.
     */
    private static final long ID_STRIDE = 1_000_000L;

    /**
     * Lower bound for the call graph layer: nodes with no source of their own, graph edges, and
     * linker findings. The whole layer is wiped and rebuilt on every scan, so it has to sit clear
     * of the ids scan derives from SOURCE.id.
     *
     * <p>Invariant: SOURCE.id stays below {@code GRAPH_ID_BASE / ID_STRIDE} (= 1,000,000).
     * Above that, a child row's id would reach into the graph layer's range and be wiped with it.
     * {@link #apply(SourceSet)} refuses the scan rather than let a source cross it.
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
        Set<String> walkedPaths = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (SourceUnit unit : s.units()) {
            walkedPaths.add(Paths.normalisedKey(unit.absPath().toString()));
        }
        for (SourceUnit unit : s.units()) {
            byte[] bytes = s.bytes().get(unit.relPath());
            // The code page in use belongs in the hash. Changing --codepage does not change the
            // bytes, so a hash of the bytes alone would never ask for the decoding to be redone.
            hashByRel.put(unit.relPath(), Paths.sha256(bytes) + ":" + Decode.overrideOf(s, unit)
                    + externalMemberHash(s, unit, walkedPaths));
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
            // A dependant is found through the edges the last scan wrote, so a file that was not
            // there then has none. Adding a PROC or INCLUDE member therefore leaves the jobs that
            // name it on the analysis they already have, exactly as adding a copybook leaves the
            // programs that COPY it; the next change to the job itself picks the member up.
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
                if (existing == null || reanalysisIds.contains(id) || dao.findNode(id).isEmpty()
                        || findingsDiffer(id, s.findingsOf(unit.relPath()).size())) {
                    targets.add(unit);
                } else {
                    skipped.add(unit.relPath());
                }
            }
            // The invariant on GRAPH_ID_BASE, checked before anything is written: past this point a
            // child row's id would land in the graph layer and be wiped with it on the next scan.
            if (maxId >= GRAPH_ID_BASE / ID_STRIDE) {
                throw new IllegalStateException("プロジェクトファイルに登録できる資産数の上限（"
                        + GRAPH_ID_BASE / ID_STRIDE + "件）に達しました。"
                        + "別のプロジェクトファイルへ取り込んでください");
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
                writeSqlScripts(targets);
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

    /**
     * The digest of every PROC or INCLUDE member this JCL expanded from outside the asset folder,
     * or the empty string when it expanded none.
     *
     * <p>A member reached through {@code --proc-path} has no SOURCE row and no hash of its own, so
     * the planner never hears of a change to it and every job that expands it keeps the steps and
     * DD statements of the member's previous content. Folding the member's digest into the job's
     * own content hash is what asks for the job to be read again, the way the code page override
     * already does.
     */
    private static String externalMemberHash(SourceSet s, SourceUnit unit,
            Set<String> walkedPaths) {
        if (unit.kind() != AssetKind.JCL) {
            return "";
        }
        Set<String> digests = new TreeSet<>();
        for (JclJobModel job : s.jobsByPath().getOrDefault(unit.relPath(), List.of())) {
            for (String member : job.members()) {
                Path path;
                try {
                    path = Path.of(member);
                } catch (InvalidPathException e) {
                    continue;
                }
                if (walkedPaths.contains(Paths.normalisedKey(member))
                        || !Files.isRegularFile(path)) {
                    continue;
                }
                try {
                    digests.add(Paths.normalisedKey(member) + "="
                            + Paths.sha256(Files.readAllBytes(path)));
                } catch (IOException e) {
                    // Parse has already told the user the member could not be read.
                }
            }
        }
        return digests.isEmpty() ? "" : ":" + String.join(",", digests);
    }

    /**
     * Whether the findings on file for {@code id} no longer match the number this run produced.
     *
     * <p>A pipeline finding may belong to a unit other than the one that brought it about: a
     * degraded EXEC SQL block standing in a copybook is filed against the copybook, though it is a
     * program copying the member that puts the block in the walk. The copybook's own bytes do not
     * change when such a program is added, so without this it would stay skipped and its finding
     * would never reach FINDING. Findings at or above {@link #GRAPH_ID_BASE} are the linker's and
     * are rewritten wholesale, so they are left out of the comparison.
     */
    private boolean findingsDiffer(long id, int fresh) {
        long persisted = dao.findFindingsBySource(id).stream()
                .filter(finding -> finding.id() < GRAPH_ID_BASE).count();
        return persisted != fresh;
    }

    /** Decode, parse and SQL findings recorded against one source, in the order they happened. */
    private List<Finding> findingsOf(SourceUnit unit) {
        return set.findingsOf(unit.relPath());
    }

    // ---- SOURCE and the findings of the sources being rewritten ----

    private void writeSources(String root, List<SourceUnit> targets,
            Map<String, SourceRecord> existingByPath, Map<String, String> hashByRel) {
        for (SourceUnit unit : targets) {
            long id = idByRel.get(unit.relPath());
            if (existingByPath.containsKey(unit.relPath())) {
                dao.deleteSourceCascade(id);
                dao.deleteCallEdgesTo(id, IncrementalAnalysisPlanner.COPY_EDGE_KIND);
                dao.deleteCallEdgesTo(id, IncrementalAnalysisPlanner.INCLUDE_EDGE_KIND);
                dao.deleteCallEdgesFrom(id, IncrementalAnalysisPlanner.EXECUTION_EDGE_KIND);
            }
            byte[] bytes = set.bytes().get(unit.relPath());
            DecodedSource decoded = set.decoded().get(unit.relPath());
            String codepage = decoded == null ? null : decoded.encoding().detectedCharset();
            dao.insertSource(new SourceRecord(id, root, unit.relPath(), codepage,
                    hashByRel.get(unit.relPath()), bytes.length));
            for (Finding finding : findingsOf(unit)) {
                writeFinding(id, finding);
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

    // ---- PROGRAM, PARAGRAPH, PARAGRAPH_EDGE and COPY edges ----

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
            List<SqlStatementModel> statements =
                    set.sqlByPath().getOrDefault(unit.relPath(), List.of());
            if (model == null) {
                upsertNode(new NodeRecord(id, nodeTypeOf(AssetKind.COBOL), unit.fileName()));
                writeSqlStatements(id, null, statements);
                continue;
            }
            upsertNode(new NodeRecord(id, nodeTypeOf(AssetKind.COBOL), model.programId()));
            dao.insertProgram(new ProgramRecord(id, id, model.programId()));
            // The PROGRAM row has to stand before an SQL statement may point at it.
            writeSqlStatements(id, id, statements);
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

    /**
     * The embedded SQL statements of one COBOL source, in the order they appear in it, DEGRADED
     * ones included: a statement the grammar would not read is still a statement, and the tables
     * and cursor a keyword scan recovered are what an inventory of the estate is built from.
     * {@code programId} is null where the source held no PROGRAM row of its own.
     */
    private void writeSqlStatements(long sourceId, Long programId,
            List<SqlStatementModel> statements) {
        // A statement taken in through a COPY carries the copybook's lines, so its own file goes
        // with them; the source it belongs to stays source_id.
        long statementSeq = 0;
        long tableUseSeq = 0;
        long columnUseSeq = 0;
        for (SqlStatementModel statement : statements) {
            long statementId = sourceId * ID_STRIDE + (++statementSeq);
            dao.insertSqlStatement(new SqlStatementRecord(statementId, sourceId, programId,
                    (int) statementSeq, statement.kind().name(),
                    statement.cursorName().orElse(null), statement.range().start().line(),
                    statement.range().end().line(), statement.analysis().name(),
                    statement.originalText(), fileOf(statement.range().start().file()),
                    sqlDetailJson(statement)));
            for (Map.Entry<String, String> entry : statement.tableAccess().entrySet()) {
                dao.insertSqlTableUse(new SqlTableUseRecord(sourceId * ID_STRIDE + (++tableUseSeq),
                        statementId, entry.getKey(), entry.getValue()));
            }
            for (SqlColumnRef column : statement.columnRefs()) {
                dao.insertSqlColumnUse(new SqlColumnUseRecord(
                        sourceId * ID_STRIDE + (++columnUseSeq), statementId,
                        column.table().orElse(null), column.column()));
            }
        }
    }

    /** Everything the SQL frontend read off one statement that its own columns do not carry. */
    private static String sqlDetailJson(SqlStatementModel statement) {
        JsonWriter writer = new JsonWriter().beginObject();
        statement.diagnostic().ifPresent(text -> writer.name("diagnostic").value(text));
        statement.positionedCursor()
                .ifPresent(cursor -> writer.name("positionedCursor").value(cursor));
        writeStrings(writer, "referencedTables", statement.referencedTables());
        statement.declaredTable().ifPresent(table -> writer.name("declaredTable").value(table));
        writeStrings(writer, "intoTargets", statement.intoTargets());
        writeStrings(writer, "selectList", statement.selectList());
        writeStrings(writer, "insertColumns", statement.insertColumns());
        writeStrings(writer, "insertValues", statement.insertValues());
        writeStrings(writer, "forUpdateColumns", statement.forUpdateColumns());
        writer.name("hostVariables").beginArray();
        statement.hostVariables().forEach(binding -> {
            writer.beginObject().name("name").value(binding.originalName());
            binding.indicatorName().ifPresent(name -> writer.name("indicator").value(name));
            writer.endObject();
        });
        writer.endArray().name("setPairs").beginArray();
        for (SqlSetPair pair : statement.setPairs()) {
            writer.beginObject().name("column").value(pair.column())
                    .name("value").value(pair.value()).endObject();
        }
        writer.endArray().name("declaredColumns").beginArray();
        for (SqlDeclaredColumn column : statement.declaredColumns()) {
            writer.beginObject().name("name").value(column.name())
                    .name("type").value(column.type())
                    .name("nullable").value(column.nullable()).endObject();
        }
        writer.endArray();
        statement.whenever().ifPresent(clause -> {
            writer.name("whenever").beginObject().name("condition").value(clause.condition());
            clause.target().ifPresent(target -> writer.name("target").value(target));
            writer.endObject();
        });
        statement.includeMember().ifPresent(member -> writer.name("includeMember").value(member));
        statement.procedureName()
                .ifPresent(procedure -> writer.name("procedureName").value(procedure));
        statement.statementName().ifPresent(name -> writer.name("statementName").value(name));
        statement.isolation().ifPresent(isolation -> writer.name("isolation").value(isolation));
        statement.rowsetSize().ifPresent(size -> writer.name("rowsetSize").value(size));
        statement.rowsetHostVariable()
                .ifPresent(name -> writer.name("rowsetHostVariable").value(name));
        writer.name("withHold").value(statement.withHold());
        writer.name("forUpdate").value(statement.forUpdate());
        writer.name("hasWhere").value(statement.hasWhere());
        writer.name("hasOrderBy").value(statement.hasOrderBy());
        writer.name("dynamic").value(statement.dynamic());
        return writer.endObject().toString();
    }

    private static void writeStrings(JsonWriter writer, String name, List<String> values) {
        writer.name(name).beginArray();
        values.forEach(writer::value);
        writer.endArray();
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
        // A member is matched by the path the resolver handed the parser, so two directories
        // holding a member of the same name stay apart. A member from outside the asset folder
        // has no row here and contributes no edge.
        Map<String, Long> jclIdByPath = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (SourceUnit unit : set.unitsOf(AssetKind.JCL)) {
            jclIdByPath.put(Paths.normalisedKey(unit.absPath().toString()),
                    idByRel.get(unit.relPath()));
        }
        // Every JCL node first, then the edges: a member -> job edge needs the member's NODE row,
        // and the member may well be written after the job that expands it.
        for (SourceUnit unit : targets) {
            if (unit.kind() != AssetKind.JCL || !set.decoded().containsKey(unit.relPath())) {
                continue;
            }
            long id = idByRel.get(unit.relPath());
            List<JclJobModel> jobs = set.jobsByPath().getOrDefault(unit.relPath(), List.of());
            // The file has one row, so the first job names it; a second job of the same file is a
            // node of the graph layer, which Persist numbers from GRAPH_ID_BASE.
            upsertNode(new NodeRecord(id, nodeTypeOf(AssetKind.JCL),
                    jobs.isEmpty() ? unit.fileName() : jobs.get(0).jobName()));
        }
        for (SourceUnit unit : targets) {
            if (unit.kind() != AssetKind.JCL || !set.decoded().containsKey(unit.relPath())) {
                continue;
            }
            long id = idByRel.get(unit.relPath());
            List<JclJobModel> jobs = set.jobsByPath().getOrDefault(unit.relPath(), List.of());
            Set<Long> executedPrograms = new TreeSet<>();
            Set<Long> expandedMembers = new TreeSet<>();
            for (JclJobModel job : jobs) {
                for (JclStep step : job.steps()) {
                    if (step.execKind() != JclExecKind.PGM) {
                        continue;
                    }
                    Long programSourceId = programIdByName.get(step.target());
                    if (programSourceId != null) {
                        executedPrograms.add(programSourceId);
                    }
                }
                for (String member : job.members()) {
                    Long memberId = jclIdByPath.get(Paths.normalisedKey(member));
                    if (memberId != null && memberId != id) {
                        expandedMembers.add(memberId);
                    }
                }
            }
            long edgeSeq = 0;
            for (long programSourceId : executedPrograms) {
                dao.insertCallEdge(new CallEdgeRecord(id * ID_STRIDE + (++edgeSeq), id,
                        programSourceId, IncrementalAnalysisPlanner.EXECUTION_EDGE_KIND,
                        null, null));
            }
            for (long memberId : expandedMembers) {
                dao.insertCallEdge(new CallEdgeRecord(id * ID_STRIDE + (++edgeSeq), memberId, id,
                        IncrementalAnalysisPlanner.INCLUDE_EDGE_KIND, null, null));
            }
            writeJclSteps(id, jobs);
        }
    }

    /**
     * The steps and DD statements of every job of one file, second and later jobs included, in the
     * order the file writes them. Both sequences run over the whole file, so a step of the second
     * job carries on where the first job's steps left off.
     *
     * <p>A DD's {@code access} is what the step's own control cards and the DISP of the DD state.
     * The refinement the executed program's FILE-CONTROL allows belongs to the call graph, where
     * the step is already tied to the program; a JCL row states what the JCL states.
     */
    private void writeJclSteps(long sourceId, List<JclJobModel> jobs) {
        long stepSeq = 0;
        long ddSeq = 0;
        for (JclJobModel job : jobs) {
            for (JclStep step : job.steps()) {
                long stepId = sourceId * ID_STRIDE + (++stepSeq);
                dao.insertJclStep(new JclStepRecord(stepId, sourceId, job.jobName(), (int) stepSeq,
                        step.name(), step.execKind().name(), step.target(),
                        step.procStepName().orElse(null), step.position().line(),
                        fileOf(step.position().file()), stepDetailJson(step)));
                int ddSeqInStep = 0;
                for (JclDdStatement dd : step.ddStatements()) {
                    dao.insertJclDd(new JclDdRecord(sourceId * ID_STRIDE + (++ddSeq), stepId,
                            ++ddSeqInStep, dd.ddName(), datasetTextOf(dd),
                            step.utility().map(facts -> facts.ddRoles()
                                            .get(dd.ddName().toUpperCase(Locale.ROOT)))
                                    .map(JclUtilityFacts.DatasetAccess::name).orElse(null),
                            dd.position().line(), fileOf(dd.position().file()),
                            ddDetailJson(dd)));
                }
            }
        }
    }

    /**
     * One position's file as SOURCE.path spells it: relative to the asset folder with forward
     * slashes, so a row names the same file the SOURCE table does and no machine's own checkout
     * directory reaches the database. A file outside the folder keeps its absolute path.
     *
     * <p>Not every position names a path at all: Che4z hands back its own {@code implicit:} URI for
     * the code it inserts itself. The row then keeps that text, because the alternative is an
     * exception thrown inside the write transaction, which would cost the whole scan.
     */
    private String fileOf(String path) {
        try {
            return Paths.relativizeOrAbsolute(set.root(), Path.of(path));
        } catch (InvalidPathException e) {
            return path;
        }
    }

    /** The data set a DD names, member and relative generation written the way the DSN spells them. */
    private static String datasetTextOf(JclDdStatement dd) {
        if (dd.dataset().isEmpty()) {
            return dd.datasetName().orElse(null);
        }
        JclDataset dataset = dd.dataset().orElseThrow();
        if (dataset.member().isPresent()) {
            return dataset.name() + "(" + dataset.member().orElseThrow() + ")";
        }
        return dataset.gdgRelative()
                .map(generation -> dataset.name() + "("
                        + (generation > 0 ? "+" + generation : String.valueOf(generation)) + ")")
                .orElseGet(dataset::name);
    }

    /** A step's EXEC parameters, its PARM, its COND and what its control cards said. */
    private static String stepDetailJson(JclStep step) {
        JsonWriter writer = new JsonWriter().beginObject();
        writeStringMap(writer, "parameters", step.parameters());
        step.parm().ifPresent(parm -> writer.name("parm").value(parm));
        step.condition().ifPresent(condition -> writer.name("condition").value(condition));
        step.utility().ifPresent(facts -> writeUtilityFacts(writer, facts));
        return writer.endObject().toString();
    }

    private static void writeUtilityFacts(JsonWriter writer, JclUtilityFacts facts) {
        writer.name("utility").beginObject();
        writer.name("programRuns").beginArray();
        for (JclUtilityFacts.ProgramRun run : facts.programRuns()) {
            writer.beginObject().name("program").value(run.program());
            run.plan().ifPresent(plan -> writer.name("plan").value(plan));
            run.parms().ifPresent(parms -> writer.name("parms").value(parms));
            run.library().ifPresent(library -> writer.name("library").value(library));
            writer.endObject();
        }
        writer.endArray().name("binds").beginArray();
        for (JclUtilityFacts.BindRequest bind : facts.binds()) {
            writer.beginObject().name("kind").value(bind.kind()).name("name").value(bind.name())
                    .name("members").beginArray();
            bind.members().forEach(writer::value);
            writer.endArray();
            writeStringMap(writer, "options", bind.options());
            writer.endObject();
        }
        writer.endArray().name("datasetUses").beginArray();
        for (JclUtilityFacts.DatasetUse use : facts.datasetUses()) {
            writer.beginObject().name("dataset").value(use.dataset())
                    .name("access").value(use.access().name()).endObject();
        }
        writer.endArray().name("tableUses").beginArray();
        for (JclUtilityFacts.TableUse use : facts.tableUses()) {
            writer.beginObject().name("table").value(use.table())
                    .name("access").value(use.access().name()).endObject();
        }
        writer.endArray().name("ddRoles").beginObject();
        facts.ddRoles().forEach((ddName, access) -> writer.name(ddName).value(access.name()));
        writer.endObject().endObject();
    }

    /** A DD's parameters, the DSN and DISP read apart, and how many in-stream lines it carries. */
    private static String ddDetailJson(JclDdStatement dd) {
        JsonWriter writer = new JsonWriter().beginObject();
        writeStringMap(writer, "parameters", dd.parameters());
        dd.dataset().ifPresent(dataset -> {
            writer.name("dataset").beginObject().name("name").value(dataset.name());
            dataset.member().ifPresent(member -> writer.name("member").value(member));
            dataset.gdgRelative()
                    .ifPresent(generation -> writer.name("gdgRelative").value(generation));
            if (dataset.temporary()) {
                writer.name("temporary").value(true);
            }
            dataset.referback().ifPresent(text -> writer.name("referback").value(text));
            writer.endObject();
        });
        dd.disposition().ifPresent(disposition -> writeDisposition(writer, disposition));
        dd.sysout().ifPresent(sysout -> writer.name("sysout").value(sysout));
        if (dd.dummy()) {
            writer.name("dummy").value(true);
        }
        writeStringMap(writer, "referbacks", dd.referbacks());
        writer.name("inStreamLines").value(dd.inStreamData().size());
        writer.name("concatIndex").value(dd.concatIndex());
        return writer.endObject().toString();
    }

    private static void writeDisposition(JsonWriter writer, JclDisposition disposition) {
        writer.name("disposition").beginObject().name("status").value(disposition.status());
        disposition.normal().ifPresent(normal -> writer.name("normal").value(normal));
        disposition.abnormal().ifPresent(abnormal -> writer.name("abnormal").value(abnormal));
        writer.name("raw").value(disposition.raw()).endObject();
    }

    private static void writeStringMap(JsonWriter writer, String name, Map<String, String> map) {
        writer.name(name).beginObject();
        map.forEach((key, value) -> writer.name(key).value(value));
        writer.endObject();
    }

    private void writeBms(List<SourceUnit> targets) {
        for (SourceUnit unit : targets) {
            if (unit.kind() != AssetKind.BMS || !set.decoded().containsKey(unit.relPath())) {
                continue;
            }
            long id = idByRel.get(unit.relPath());
            List<BmsMapset> mapsets = set.mapsetsByPath().getOrDefault(unit.relPath(), List.of());
            String label = mapsets.isEmpty() ? unit.fileName() : mapsets.get(0).name();
            upsertNode(new NodeRecord(id, nodeTypeOf(AssetKind.BMS), label));
        }
    }

    /**
     * An SQL script and the statements it holds. The script is the file, not a program, so the node
     * is labelled by the file name and the statements carry no program: what a script runs, it runs
     * on its own. A routine the script defines is a node of the call graph layer, written with it.
     */
    private void writeSqlScripts(List<SourceUnit> targets) {
        for (SourceUnit unit : targets) {
            if (unit.kind() != AssetKind.SQL || !set.decoded().containsKey(unit.relPath())) {
                continue;
            }
            long id = idByRel.get(unit.relPath());
            upsertNode(new NodeRecord(id, nodeTypeOf(AssetKind.SQL), unit.fileName()));
            writeSqlStatements(id, null,
                    set.sqlByPath().getOrDefault(unit.relPath(), List.of()));
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
        // Only the first job of a file can reuse its SOURCE row; the rest are graph-layer nodes.
        Map<String, Long> jobSourceIdByName = new HashMap<>();
        set.jobsByPath().forEach((relPath, jobs) -> {
            if (!jobs.isEmpty()) {
                jobSourceIdByName.putIfAbsent(jobs.get(0).jobName(), idByRel.get(relPath));
            }
        });
        Map<String, Long> sourceBackedByGraphNodeId = new HashMap<>();
        for (CallGraphNode node : result.graph().nodes()) {
            Long sourceBacked = switch (node.kind()) {
                // Only a node of the program space may reuse a program's row. A routine an SQL
                // script defines is typed PROGRAM as well, and one named like a COBOL program would
                // otherwise take that program's row and its calls with it.
                case PROGRAM -> node.id().startsWith(CallGraphLinker.PROGRAM_ID_PREFIX)
                        ? programSourceIdByName.get(node.label()) : null;
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
                    edge.seq(), edge.line(), edge.attributes().get("access"),
                    attributesJson(edge.attributes())));
        }
    }

    /**
     * The attributes an edge carries beyond {@code access}, as a JSON object, or null where it
     * carries none. {@code access} has a column of its own, so repeating it here would be two
     * places to keep in step for nothing.
     */
    private static String attributesJson(Map<String, String> attributes) {
        Map<String, String> rest = new TreeMap<>(attributes);
        rest.remove("access");
        if (rest.isEmpty()) {
            return null;
        }
        JsonWriter writer = new JsonWriter().beginObject();
        rest.forEach((key, value) -> writer.name(key).value(value));
        return writer.endObject().toString();
    }

    /**
     * Writes the linker's findings — its record of how each call was resolved — with ids running
     * from {@link #GRAPH_ID_BASE}. Invariant: a scan finding's id ({@code SOURCE.id × ID_STRIDE +
     * sequence}) stays clear of that base while SOURCE.id is below 1,000,000.
     */
    private void writeLinkerFindings(List<Finding> findings) {
        // A finding's file is the program the call stands in, unless the call was copied in: a
        // statement taken from a COPY member carries the member's own path. Every unit of the walk
        // is a candidate, so such a finding is filed against the member instead of being dropped.
        Map<String, Long> sourceIdByFile = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (SourceUnit unit : set.units()) {
            sourceIdByFile.put(Paths.normalisedKey(unit.absPath().toString()),
                    idByRel.get(unit.relPath()));
        }
        set.programsByPath().forEach((relPath, model) ->
                sourceIdByFile.put(Paths.normalisedKey(model.sourceFile()), idByRel.get(relPath)));
        long findingId = GRAPH_ID_BASE;
        for (Finding finding : findings) {
            Long sourceId = sourceIdOf(sourceIdByFile, finding.location().file());
            if (sourceId == null) {
                System.err.println("警告: linker finding の対象の原始プログラムを特定できないため保存しません: "
                        + finding.location().file() + " (" + finding.ruleId() + ")");
                continue;
            }
            dao.insertFinding(new FindingRecord(findingId++, finding.ruleId(),
                    finding.level().name(), sourceId, finding.location().line(),
                    finding.location().column(), finding.message()));
        }
    }

    /** The SOURCE.id of the file a finding names, or null when the walk never saw that file. */
    private static Long sourceIdOf(Map<String, Long> sourceIdByFile, String file) {
        try {
            return sourceIdByFile.get(Paths.normalisedKey(file));
        } catch (InvalidPathException e) {
            return null;
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
            case SQL -> "SQL";
        };
    }

    private void upsertNode(NodeRecord node) {
        if (dao.findNode(node.id()).isPresent()) {
            dao.updateNode(node);
        } else {
            dao.insertNode(node);
        }
    }

    private void writeFinding(long sourceId, Finding finding) {
        int seq = findingSeqBySource.merge(sourceId, 1, Integer::sum);
        dao.insertFinding(new FindingRecord(sourceId * ID_STRIDE + seq, finding.ruleId(),
                finding.level().name(), sourceId, finding.location().line(),
                finding.location().column(), finding.message()));
    }
}
