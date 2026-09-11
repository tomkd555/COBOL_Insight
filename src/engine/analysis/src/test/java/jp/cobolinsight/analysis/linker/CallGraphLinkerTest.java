package jp.cobolinsight.analysis.linker;

import jp.cobolinsight.core.bms.BmsField;
import jp.cobolinsight.core.bms.BmsMap;
import jp.cobolinsight.core.bms.BmsMapset;
import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.callgraph.CallGraphNode;
import jp.cobolinsight.core.callgraph.EdgeKind;
import jp.cobolinsight.core.callgraph.NodeKind;
import jp.cobolinsight.core.callgraph.Resolution;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.jcl.JclDataset;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclDisposition;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.jcl.JclUtilityFacts;
import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetAccess;
import jp.cobolinsight.core.semantic.CallKind;
import jp.cobolinsight.core.semantic.CallRelation;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.ControlKind;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.semantic.FileAccess;
import jp.cobolinsight.core.semantic.FileDefinition;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.ProcedureKind;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.sql.SqlAnalysis;
import jp.cobolinsight.core.sql.SqlDeclaredColumn;
import jp.cobolinsight.core.sql.SqlRoutineDefinition;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;
import jp.cobolinsight.core.sql.SqlStructureSignals;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallGraphLinkerTest {

    private static SourceRange range(String file, int line) {
        return new SourceRange(new SourcePosition(file, line, 1, -1),
                new SourcePosition(file, line, 2, -1));
    }

    private static SimpleStatement stmt(String verb, String text, String file, int line) {
        return new SimpleStatement(verb, text, range(file, line));
    }

    private static CobolSemanticModel program(String programId, List<Statement> statements,
            List<CallRelation> calls, List<EmbeddedBlock> embeddedBlocks) {
        String file = programId + ".cbl";
        Procedure main = new Procedure("0000-MAIN", ProcedureKind.PARAGRAPH, Optional.empty(),
                statements, range(file, 10));
        return new CobolSemanticModel(programId, file, List.of(), List.of(main), calls, List.of(),
                embeddedBlocks, List.of(), List.of());
    }

    private static JclJobModel job(String jobName, List<JclStep> steps) {
        return new JclJobModel(jobName, jobName + ".jcl", Optional.empty(), steps);
    }

    private static JclStep pgmStep(String jobName, String stepName, String target,
            List<JclDdStatement> dds) {
        return new JclStep(stepName, JclExecKind.PGM, target, Optional.empty(), dds,
                SourcePosition.fileStart(jobName + ".jcl"));
    }

    private static JclDdStatement dd(String ddName, String dsn) {
        return new JclDdStatement(ddName, Optional.ofNullable(dsn), Optional.empty(),
                SourcePosition.fileStart("JOB1.jcl"));
    }

    /** A DD whose DSN carries a relative generation, and whose DISP says how the step uses it. */
    private static JclDdStatement gdgDd(String ddName, String name, int generation, String disp) {
        return new JclDdStatement(ddName, Optional.of(name + "(+" + generation + ")"),
                Optional.of(new JclDataset(name, Optional.empty(), Optional.of(generation), false,
                        Optional.empty())),
                Optional.of(disp),
                Optional.of(new JclDisposition(disp, Optional.empty(), Optional.empty(), disp)),
                Optional.empty(), false, Map.of(), Map.of(), List.of(), 0,
                SourcePosition.fileStart("JOB1.jcl"));
    }

    private static CobolSemanticModel programWithFiles(String programId,
            List<FileDefinition> files) {
        String file = programId + ".cbl";
        Procedure main = new Procedure("0000-MAIN", ProcedureKind.PARAGRAPH, Optional.empty(),
                List.of(), range(file, 10));
        return new CobolSemanticModel(programId, file, List.of(), List.of(main), List.of(),
                List.of(), List.of(), List.of(), List.of(), files);
    }

    private static FileDefinition file(String fileName, String ddName, FileAccess... accesses) {
        return new FileDefinition(fileName, Optional.of(ddName), Optional.of("SEQUENTIAL"),
                Set.of(accesses), SourcePosition.fileStart(fileName + ".cbl"));
    }

    private static JclStep stepWithFacts(String stepName, String target,
            List<JclDdStatement> dds, JclUtilityFacts facts) {
        return new JclStep(stepName, JclExecKind.PGM, target, Optional.empty(), dds, Map.of(),
                Optional.empty(), Optional.empty(), Optional.of(facts),
                SourcePosition.fileStart("JOB1.jcl"));
    }

    /** One embedded SQL statement with the facts the caller sets on its builder. */
    private static SqlStatementModel sql(SqlStatementKind kind, String text, int line,
            UnaryOperator<SqlStatementModel.Builder> facts) {
        return facts.apply(new SqlStatementModel.Builder().kind(kind))
                .build(text, text, List.of(), range("SQLPGM.cbl", line),
                        SqlStructureSignals.empty(), SqlAnalysis.FULL, Optional.empty());
    }

    private static CallGraphNode node(LinkResult result, String id) {
        return result.graph().nodes().stream().filter(n -> n.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("node not found: " + id + " in "
                        + result.graph().nodes()));
    }

    private static boolean hasEdge(LinkResult result, String from, String to, EdgeKind kind,
            Resolution resolution) {
        return result.graph().edges()
                .contains(new CallGraphEdge(from, to, kind, resolution));
    }

    private static CallGraphEdge edge(LinkResult result, String from, String to) {
        return result.graph().edges().stream()
                .filter(e -> e.fromId().equals(from) && e.toId().equals(to)).findFirst()
                .orElseThrow(() -> new AssertionError("edge not found: " + from + " -> " + to
                        + " in " + result.graph().toJson()));
    }

    // ---- JCL: EXEC PGM=, dataset references, external utilities ----

    @Test
    void linksJobStepProgramAndDatasets() {
        CobolSemanticModel main = program("PGMA", List.of(), List.of(), List.of());
        JclJobModel jobModel = job("JOB1", List.of(pgmStep("JOB1", "STEP010", "PGMA", List.of(
                dd("IN1", "SYKT.INPUT.DATA"),
                dd("STEPLIB", "SYK.PROD.LOADLIB"),
                dd("SYSOUT", null)))));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(main), List.of(jobModel),
                List.of(), Map.of(), Map.of()));

        assertEquals(NodeKind.JOB, node(result, "job:JOB1").kind());
        assertEquals(NodeKind.STEP, node(result, "step:JOB1.STEP010").kind());
        assertEquals(NodeKind.PROGRAM, node(result, "program:PGMA").kind());
        assertEquals(NodeKind.DATASET, node(result, "dataset:SYKT.INPUT.DATA").kind());
        assertTrue(hasEdge(result, "job:JOB1", "step:JOB1.STEP010", EdgeKind.EXECUTION,
                Resolution.CONSTANT));
        assertTrue(hasEdge(result, "step:JOB1.STEP010", "program:PGMA", EdgeKind.EXECUTION,
                Resolution.CONSTANT));
        assertTrue(hasEdge(result, "step:JOB1.STEP010", "dataset:SYKT.INPUT.DATA",
                EdgeKind.REFERENCE, Resolution.CONSTANT));
        // STEPLIB (load library) and a DD with no DSN do not become dataset edges
        assertTrue(result.graph().nodes().stream()
                .noneMatch(n -> n.id().equals("dataset:SYK.PROD.LOADLIB")));
    }

    @Test
    void typesKnownUtilitiesAsExternalUtilityLeaf() {
        JclJobModel jobModel = job("JOB1", List.of(
                pgmStep("JOB1", "STEP010", "DFSORT", List.of()),
                pgmStep("JOB1", "STEP020", "IDCAMS", List.of()),
                pgmStep("JOB1", "STEP030", "IEBGENER", List.of())));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(), List.of(jobModel),
                List.of(), Map.of(), Map.of()));

        for (String utility : List.of("DFSORT", "IDCAMS", "IEBGENER")) {
            CallGraphNode node = node(result, "utility:" + utility);
            assertEquals(NodeKind.EXTERNAL_UTILITY, node.kind());
            assertEquals(utility, node.attributes().get("utility"), "種別タグを属性に保持する");
        }
        assertTrue(hasEdge(result, "step:JOB1.STEP010", "utility:DFSORT", EdgeKind.EXECUTION,
                Resolution.CONSTANT));
    }

    @Test
    void typesUnknownExecTargetAsExternalProgram() {
        JclJobModel jobModel = job("JOB1",
                List.of(pgmStep("JOB1", "STEP010", "PLIPGM1", List.of())));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(), List.of(jobModel),
                List.of(), Map.of(), Map.of()));

        CallGraphNode node = node(result, "program:PLIPGM1");
        assertEquals(NodeKind.PROGRAM, node.kind());
        assertEquals("true", node.attributes().get("external"), "ソース無しの外部プログラムと分かること");
    }

    /**
     * Under {@code lint --scope} part of the COBOL is never read, so an unresolved callee may
     * well be in the folder. Every one of them says so, so that a rule about unanalysed callees
     * leaves them alone; a whole-folder run marks none of them.
     */
    @Test
    void marksEveryUnresolvedTargetOfAScopedRunAsOutsideTheScope() {
        JclJobModel jobModel = job("JOB1", List.of(pgmStep("JOB1", "STEP010", "PGMOUT", List.of())));
        LinkResult scoped = CallGraphLinker.link(new LinkerInput(List.of(), List.of(jobModel),
                List.of(), Map.of(), Map.of(), Map.of(), List.of(), true));
        LinkResult wholeFolder = CallGraphLinker.link(new LinkerInput(List.of(), List.of(jobModel),
                List.of(), Map.of(), Map.of(), Map.of(), List.of(), false));

        CallGraphNode outside = node(scoped, "program:PGMOUT");
        assertEquals("true", outside.attributes().get("external"));
        assertEquals("true", outside.attributes().get("outsideScope"),
                "範囲を絞った走行では範囲外かもしれないと分かること");
        assertNull(node(wholeFolder, "program:PGMOUT").attributes().get("outsideScope"),
                "資産フォルダ全体の走行では原始プログラムが無いと分かること");
    }

    @Test
    void skipsProcStepsAndLinksExpandedSteps() {
        CobolSemanticModel main = program("PGMB", List.of(), List.of(), List.of());
        JclStep procCall = new JclStep("STEP020", JclExecKind.PROC, "PRC1", Optional.empty(),
                List.of(), SourcePosition.fileStart("JOB1.jcl"));
        JclJobModel jobModel = job("JOB1", List.of(procCall,
                pgmStep("JOB1", "STEP020.STEP020", "PGMB", List.of())));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(main), List.of(jobModel),
                List.of(), Map.of(), Map.of()));

        assertTrue(result.graph().nodes().stream().noneMatch(n -> n.id().equals("step:JOB1.STEP020")),
                "PROC呼出ステップ自体はノードにしない(展開後ステップが担う)");
        assertTrue(hasEdge(result, "step:JOB1.STEP020.STEP020", "program:PGMB", EdgeKind.EXECUTION,
                Resolution.CONSTANT));
    }

    @Test
    void keepsJclStepOrderAsEdgeSeqWithCallSiteLine() {
        CobolSemanticModel first = program("PGMA", List.of(), List.of(), List.of());
        CobolSemanticModel second = program("PGMB", List.of(), List.of(), List.of());
        JclStep step010 = new JclStep("STEP010", JclExecKind.PGM, "PGMA", Optional.empty(),
                List.of(), new SourcePosition("JOB1.jcl", 4, 1, -1));
        JclStep step020 = new JclStep("STEP020", JclExecKind.PGM, "PGMB", Optional.empty(),
                List.of(), new SourcePosition("JOB1.jcl", 9, 1, -1));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(first, second),
                List.of(job("JOB1", List.of(step010, step020))), List.of(), Map.of(), Map.of()));

        // A job's outgoing edges are numbered from 1 in the original step order, and the line points to the EXEC statement's line
        assertEquals(1, edge(result, "job:JOB1", "step:JOB1.STEP010").seq());
        assertEquals(4, edge(result, "job:JOB1", "step:JOB1.STEP010").line());
        assertEquals(2, edge(result, "job:JOB1", "step:JOB1.STEP020").seq());
        assertEquals(9, edge(result, "job:JOB1", "step:JOB1.STEP020").line());
        // The edge from a step to its program is that step's first outgoing edge
        assertEquals(1, edge(result, "step:JOB1.STEP020", "program:PGMB").seq());
    }

    /**
     * A single caller's outgoing edges must be numbered in the original line order even though the
     * edge-building process is split up. CALL, EXEC CICS, and Db2 table references each add edges
     * in a separate pass, so if edges were left numbered in add order, an earlier-line EXEC CICS
     * would get a number after a later-line CALL.
     */
    @Test
    void edgeSeqFollowsSourceLineAcrossTheSeparatePasses() {
        CallRelation call = new CallRelation("PGMA", CallKind.STATIC, "PGMB",
                range("PGMA.cbl", 300));
        EmbeddedBlock xctl = new EmbeddedBlock(EmbeddedBlockKind.CICS_XCTL, "EXEC CICS XCTL",
                Map.of("PROGRAM", "PGMC"), range("PGMA.cbl", 100));
        SqlStatementModel select = new SqlStatementModel(SqlStatementKind.SELECT,
                "SELECT A FROM T_ORDER", "SELECT A FROM T_ORDER", List.of(), List.of("T_ORDER"),
                range("PGMA.cbl", 200), SqlStructureSignals.empty());
        CobolSemanticModel caller = program("PGMA", List.of(), List.of(call), List.of(xctl));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(caller), List.of(),
                List.of(), Map.of("PGMA", List.of(select)), Map.of()));

        assertEquals(1, edge(result, "program:PGMA", "program:PGMC").seq(),
                "100行目の EXEC CICS XCTL が1本目");
        assertEquals(2, edge(result, "program:PGMA", "db2:T_ORDER").seq(),
                "200行目のSQLが2本目");
        assertEquals(3, edge(result, "program:PGMA", "program:PGMB").seq(),
                "300行目の CALL が3本目");
    }

    // ---- CALL: static, dynamic (constant propagation), unresolved ----

    @Test
    void linksStaticCallAsConstant() {
        CallRelation call = new CallRelation("PGMA", CallKind.STATIC, "PGMB",
                range("PGMA.cbl", 110));
        CobolSemanticModel caller = program("PGMA", List.of(), List.of(call), List.of());
        CobolSemanticModel callee = program("PGMB", List.of(), List.of(), List.of());
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(caller, callee),
                List.of(), List.of(), Map.of(), Map.of()));

        assertTrue(hasEdge(result, "program:PGMA", "program:PGMB", EdgeKind.CALL,
                Resolution.CONSTANT));
    }

    @Test
    void deduplicatesRepeatedCallsToSameTarget() {
        CallRelation first = new CallRelation("PGMA", CallKind.STATIC, "PGMB",
                range("PGMA.cbl", 133));
        CallRelation second = new CallRelation("PGMA", CallKind.STATIC, "PGMB",
                range("PGMA.cbl", 142));
        CobolSemanticModel caller = program("PGMA", List.of(), List.of(first, second), List.of());
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(caller), List.of(),
                List.of(), Map.of(), Map.of()));

        long count = result.graph().edges().stream()
                .filter(e -> e.fromId().equals("program:PGMA") && e.toId().equals("program:PGMB"))
                .count();
        assertEquals(1, count, "同一呼出先への複数CALLは1辺に正規化する");
        assertEquals(133, edge(result, "program:PGMA", "program:PGMB").line(),
                "畳んだ辺は最初の呼出箇所の行を持つ");
    }

    @Test
    void resolvesDynamicCallByMoveConstantPropagation() {
        Statement move = stmt("MOVE", "MOVE 'PGMB' TO WS-PROG-NAME", "PGMA.cbl", 70);
        CallRelation call = new CallRelation("PGMA", CallKind.DYNAMIC, "WS-PROG-NAME",
                range("PGMA.cbl", 113));
        CobolSemanticModel caller = program("PGMA", List.of(move), List.of(call), List.of());
        CobolSemanticModel callee = program("PGMB", List.of(), List.of(), List.of());
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(caller, callee),
                List.of(), List.of(), Map.of(), Map.of()));

        CallGraphEdge edge = new CallGraphEdge("program:PGMA", "program:PGMB", EdgeKind.CALL,
                Resolution.CONSTANT);
        assertTrue(result.graph().edges().contains(edge), () -> "辺が無い: " + result.graph().toJson());
        assertEquals(Set.of("WS-PROG-NAME"), result.dynamicCallVariables().get(edge));

        Finding finding = result.findings().stream()
                .filter(f -> f.ruleId().equals(CallGraphLinker.DYNAMIC_CALL_RESOLVED_RULE_ID))
                .findFirst().orElseThrow();
        assertEquals(FindingLevel.NOTE, finding.level());
        assertEquals(113, finding.location().line());
        assertTrue(finding.message().contains("定数由来"), finding.message());
        assertTrue(finding.message().contains("WS-PROG-NAME"), finding.message());
    }

    @Test
    void resolvesDynamicCallFromMoveInsideCompoundStatement() {
        Statement inner = stmt("MOVE", "MOVE 'PGMC' TO WS-PROG-NAME", "PGMA.cbl", 71);
        Statement branch = new CompoundStatement(ControlKind.BRANCH, "WS-FLAG = '1'",
                List.of(new StatementBlock("THEN", List.of(inner))), range("PGMA.cbl", 70));
        CallRelation call = new CallRelation("PGMA", CallKind.DYNAMIC, "WS-PROG-NAME",
                range("PGMA.cbl", 113));
        CobolSemanticModel caller = program("PGMA", List.of(branch), List.of(call), List.of());
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(caller), List.of(),
                List.of(), Map.of(), Map.of()));

        assertTrue(hasEdge(result, "program:PGMA", "program:PGMC", EdgeKind.CALL,
                Resolution.CONSTANT), "入れ子のMOVEも定数伝播の対象とする");
    }

    @Test
    void resolvesDynamicCallToEveryMoveConstantOfTheVariable() {
        Statement first = stmt("MOVE", "MOVE 'PGMB' TO WS-PROG-NAME", "PGMA.cbl", 70);
        Statement second = stmt("MOVE", "MOVE 'PGMC' TO WS-PROG-NAME", "PGMA.cbl", 80);
        CallRelation call = new CallRelation("PGMA", CallKind.DYNAMIC, "WS-PROG-NAME",
                range("PGMA.cbl", 113));
        CobolSemanticModel caller = program("PGMA", List.of(first, second), List.of(call),
                List.of());
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(caller), List.of(),
                List.of(), Map.of(), Map.of()));

        assertTrue(hasEdge(result, "program:PGMA", "program:PGMB", EdgeKind.CALL,
                Resolution.CONSTANT), "同一変数への複数MOVEは全候補へ辺を張る");
        assertTrue(hasEdge(result, "program:PGMA", "program:PGMC", EdgeKind.CALL,
                Resolution.CONSTANT), "同一変数への複数MOVEは全候補へ辺を張る");
    }

    @Test
    void resolvesBranchLocalMovesWithoutFlowSensitivity() {
        // Pinning down the over-approximation spec: MOVEs in both the THEN and ELSE branches are
        // taken as candidates regardless of execution order or the branch condition
        Statement thenMove = stmt("MOVE", "MOVE 'PGMB' TO WS-PROG-NAME", "PGMA.cbl", 71);
        Statement elseMove = stmt("MOVE", "MOVE 'PGMC' TO WS-PROG-NAME", "PGMA.cbl", 73);
        Statement branch = new CompoundStatement(ControlKind.BRANCH, "WS-FLAG = '1'",
                List.of(new StatementBlock("THEN", List.of(thenMove)),
                        new StatementBlock("ELSE", List.of(elseMove))), range("PGMA.cbl", 70));
        CallRelation call = new CallRelation("PGMA", CallKind.DYNAMIC, "WS-PROG-NAME",
                range("PGMA.cbl", 113));
        CobolSemanticModel caller = program("PGMA", List.of(branch), List.of(call), List.of());
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(caller), List.of(),
                List.of(), Map.of(), Map.of()));

        assertTrue(hasEdge(result, "program:PGMA", "program:PGMB", EdgeKind.CALL,
                Resolution.CONSTANT), "分岐内MOVEも候補とする(過大近似)");
        assertTrue(hasEdge(result, "program:PGMA", "program:PGMC", EdgeKind.CALL,
                Resolution.CONSTANT), "分岐内MOVEも候補とする(過大近似)");
        Finding finding = result.findings().stream()
                .filter(f -> f.ruleId().equals(CallGraphLinker.DYNAMIC_CALL_RESOLVED_RULE_ID))
                .findFirst().orElseThrow();
        assertTrue(finding.message().contains("分岐・実行順序を考慮しない"),
                "候補が分岐・実行順序を考慮しない全MOVE定数由来である旨を明記する: "
                        + finding.message());
    }

    @Test
    void recordsEveryVariableResolvingToTheSameEdge() {
        Statement moveA = stmt("MOVE", "MOVE 'PGMB' TO WS-A", "PGMA.cbl", 70);
        Statement moveB = stmt("MOVE", "MOVE 'PGMB' TO WS-B", "PGMA.cbl", 71);
        CallRelation viaA = new CallRelation("PGMA", CallKind.DYNAMIC, "WS-A",
                range("PGMA.cbl", 110));
        CallRelation viaB = new CallRelation("PGMA", CallKind.DYNAMIC, "WS-B",
                range("PGMA.cbl", 120));
        CobolSemanticModel caller = program("PGMA", List.of(moveA, moveB),
                List.of(viaA, viaB), List.of());
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(caller), List.of(),
                List.of(), Map.of(), Map.of()));

        CallGraphEdge edge = new CallGraphEdge("program:PGMA", "program:PGMB", EdgeKind.CALL,
                Resolution.CONSTANT);
        assertEquals(Set.of("WS-A", "WS-B"), result.dynamicCallVariables().get(edge),
                "同一辺へ解決した全変数名を保持する");
        long resolvedFindings = result.findings().stream()
                .filter(f -> f.ruleId().equals(CallGraphLinker.DYNAMIC_CALL_RESOLVED_RULE_ID))
                .count();
        assertEquals(2, resolvedFindings, "変数ごとに解決根拠のfindingが残る");
    }

    @Test
    void mergesLowercaseProgramIdWithUppercaseReferences() {
        CobolSemanticModel main = program("pgma", List.of(), List.of(), List.of());
        JclJobModel jobModel = job("JOB1",
                List.of(pgmStep("JOB1", "STEP010", "PGMA", List.of())));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(main), List.of(jobModel),
                List.of(), Map.of(), Map.of()));

        CallGraphNode node = node(result, "program:PGMA");
        assertTrue(node.attributes().isEmpty(), "実体ソースのあるプログラムは外部型付けしない");
        assertTrue(result.graph().nodes().stream().noneMatch(n -> n.id().equals("program:pgma")),
                "小文字PROGRAM-IDが別ノードへ二重化しないこと");
        assertTrue(hasEdge(result, "step:JOB1.STEP010", "program:PGMA", EdgeKind.EXECUTION,
                Resolution.CONSTANT));
    }

    @Test
    void registersHeadDataNameOfQualifiedMoveTarget() {
        Statement move = stmt("MOVE", "MOVE 'PGMB' TO WS-NAME OF WS-REC", "PGMA.cbl", 70);
        CallRelation viaName = new CallRelation("PGMA", CallKind.DYNAMIC, "WS-NAME",
                range("PGMA.cbl", 110));
        CallRelation viaQualifier = new CallRelation("PGMA", CallKind.DYNAMIC, "WS-REC",
                range("PGMA.cbl", 120));
        CobolSemanticModel caller = program("PGMA", List.of(move),
                List.of(viaName, viaQualifier), List.of());
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(caller), List.of(),
                List.of(), Map.of(), Map.of()));

        assertTrue(hasEdge(result, "program:PGMA", "program:PGMB", EdgeKind.CALL,
                Resolution.CONSTANT), "修飾名MOVEは先頭データ名で登録する");
        assertTrue(hasEdge(result, "program:PGMA", "unresolved:PGMA.WS-REC", EdgeKind.CALL,
                Resolution.UNRESOLVED), "修飾語(OF句の修飾名)は定数の設定先として登録しない");
    }

    @Test
    void keepsUnresolvedDynamicCallAsTypedBoundaryNode() {
        CallRelation call = new CallRelation("PGMA", CallKind.DYNAMIC, "WS-PROG-NAME",
                range("PGMA.cbl", 113));
        CobolSemanticModel caller = program("PGMA", List.of(), List.of(call), List.of());
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(caller), List.of(),
                List.of(), Map.of(), Map.of()));

        CallGraphNode node = node(result, "unresolved:PGMA.WS-PROG-NAME");
        assertEquals(NodeKind.UNRESOLVED, node.kind());
        assertEquals("WS-PROG-NAME", node.attributes().get("variable"), "指定変数名を保持する");
        assertTrue(hasEdge(result, "program:PGMA", "unresolved:PGMA.WS-PROG-NAME", EdgeKind.CALL,
                Resolution.UNRESOLVED));
        assertTrue(result.findings().stream().anyMatch(f ->
                        f.ruleId().equals(CallGraphLinker.DYNAMIC_CALL_UNRESOLVED_RULE_ID)
                                && f.level() == FindingLevel.NOTE),
                "未解決の動的CALLをNOTEのfindingとして記録する");
    }

    // ---- EXEC CICS: transition edges, map-reference edges, transaction resolution ----

    private static EmbeddedBlock cics(EmbeddedBlockKind kind, Map<String, String> operands,
            String file, int line) {
        return new EmbeddedBlock(kind, "EXEC CICS " + kind, operands, range(file, line));
    }

    @Test
    void linksCicsTransitionsAndMapReferences() {
        CobolSemanticModel pgm8 = program("PGM8", List.of(), List.of(), List.of(
                cics(EmbeddedBlockKind.CICS_RECEIVE_MAP,
                        Map.of("MAP", "SYKM01", "MAPSET", "SYKMAP1"), "PGM8.cbl", 34),
                cics(EmbeddedBlockKind.CICS_SEND_MAP,
                        Map.of("MAP", "SYKM99", "MAPSET", "SYKMAP1"), "PGM8.cbl", 55),
                cics(EmbeddedBlockKind.CICS_RETURN_TRANSID, Map.of("TRANSID", "SYK8"),
                        "PGM8.cbl", 66),
                cics(EmbeddedBlockKind.CICS_XCTL, Map.of("PROGRAM", "PGM9"), "PGM8.cbl", 76)));
        CobolSemanticModel pgm9 = program("PGM9", List.of(), List.of(), List.of());
        BmsMapset mapset = new BmsMapset("SYKMAP1", "SYKMAP1.bms", List.of(new BmsMap("SYKM01",
                24, 80, List.of(new BmsField("ORDNO", 3, 10, 8, "UNPROT,NUM")))));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(pgm8, pgm9), List.of(),
                List.of(mapset), Map.of(), Map.of("SYK8", "PGM8")));

        assertTrue(hasEdge(result, "program:PGM8", "program:PGM9",
                EdgeKind.TRANSACTION_TRANSITION, Resolution.CONSTANT), "XCTL遷移辺");
        assertTrue(hasEdge(result, "program:PGM8", "transaction:SYK8",
                EdgeKind.TRANSACTION_TRANSITION, Resolution.CONSTANT), "RETURN TRANSID遷移辺");
        assertTrue(hasEdge(result, "transaction:SYK8", "program:PGM8",
                EdgeKind.TRANSACTION_TRANSITION, Resolution.CONSTANT),
                "トランザクション定義表によるID→プログラム解決辺");
        assertEquals(NodeKind.TRANSACTION, node(result, "transaction:SYK8").kind());
        assertTrue(hasEdge(result, "program:PGM8", "bmsmap:SYKMAP1.SYKM01", EdgeKind.MAP_REFERENCE,
                Resolution.CONSTANT), "RECEIVE MAPのマップ参照辺");
        assertTrue(hasEdge(result, "program:PGM8", "bmsmap:SYKMAP1.SYKM99", EdgeKind.MAP_REFERENCE,
                Resolution.CONSTANT), "未定義マップでも参照辺を張る(存在検査はR031の責務)");
        assertEquals(NodeKind.BMS_MAP, node(result, "bmsmap:SYKMAP1.SYKM01").kind());
        assertEquals(NodeKind.BMS_MAP, node(result, "bmsmap:SYKMAP1.SYKM99").kind());
    }

    @Test
    void resolvesCicsProgramOperandGivenAsVariable() {
        DataItem variable = new DataItem(1, "WS-NEXT-PGM", Optional.of("X(08)"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(),
                new SourcePosition("PGM8.cbl", 20, 1, -1));
        Statement move = stmt("MOVE", "MOVE 'PGM9' TO WS-NEXT-PGM", "PGM8.cbl", 40);
        Procedure main = new Procedure("0000-MAIN", ProcedureKind.PARAGRAPH, Optional.empty(),
                List.of(move), range("PGM8.cbl", 40));
        CobolSemanticModel pgm8 = new CobolSemanticModel("PGM8", "PGM8.cbl", List.of(variable),
                List.of(main), List.of(), List.of(),
                List.of(cics(EmbeddedBlockKind.CICS_XCTL, Map.of("PROGRAM", "WS-NEXT-PGM"),
                        "PGM8.cbl", 76)),
                List.of(), List.of());
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(pgm8), List.of(),
                List.of(), Map.of(), Map.of()));

        assertTrue(hasEdge(result, "program:PGM8", "program:PGM9",
                EdgeKind.TRANSACTION_TRANSITION, Resolution.CONSTANT),
                "変数指定のXCTLはMOVE定数伝播で飛び先を解決する");
        assertTrue(result.graph().nodes().stream()
                        .noneMatch(n -> n.id().equals("program:WS-NEXT-PGM")),
                "データ名をプログラムのノードにしないこと");
    }

    @Test
    void linksCicsLinkAndStart() {
        CobolSemanticModel pgm1 = program("PGM1", List.of(), List.of(), List.of(
                cics(EmbeddedBlockKind.CICS_LINK, Map.of("PROGRAM", "PGM2"), "PGM1.cbl", 20),
                cics(EmbeddedBlockKind.CICS_START, Map.of("TRANSID", "SYK9"), "PGM1.cbl", 30)));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(pgm1), List.of(),
                List.of(), Map.of(), Map.of()));

        assertTrue(hasEdge(result, "program:PGM1", "program:PGM2",
                EdgeKind.CALL, Resolution.CONSTANT), "LINK は呼出辺（呼出元へ戻る）");
        assertTrue(hasEdge(result, "program:PGM1", "transaction:SYK9",
                EdgeKind.TRANSACTION_TRANSITION, Resolution.CONSTANT), "START TRANSID遷移辺");
        // A transaction absent from the definition table remains a leaf with no resolving edge to a program
        assertTrue(result.graph().edges().stream()
                .noneMatch(e -> e.fromId().equals("transaction:SYK9")));
        assertTrue(result.findings().stream().anyMatch(f ->
                f.ruleId().equals(CallGraphLinker.TRANSACTION_UNRESOLVED_RULE_ID)
                        && f.level() == FindingLevel.NOTE));
    }

    // ---- Db2 table references ----

    @Test
    void linksDb2TableReferences() {
        CobolSemanticModel pgm = program("PGMD", List.of(), List.of(), List.of());
        SqlStatementModel select = new SqlStatementModel(SqlStatementKind.SELECT,
                "SELECT A FROM SYKDB.ZAIKOM", "SELECT A FROM SYKDB.ZAIKOM", List.of(),
                List.of("SYKDB.ZAIKOM"), range("PGMD.cbl", 96), SqlStructureSignals.empty());
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(pgm), List.of(),
                List.of(), Map.of("PGMD", List.of(select)), Map.of()));

        assertEquals(NodeKind.DB2_TABLE, node(result, "db2:SYKDB.ZAIKOM").kind());
        assertTrue(hasEdge(result, "program:PGMD", "db2:SYKDB.ZAIKOM", EdgeKind.REFERENCE,
                Resolution.CONSTANT));
    }

    // ---- how a step uses a DD, and what its control cards say ----

    /**
     * The FILE-CONTROL of the program the step runs decides the access wherever the DD name matches
     * one of its ASSIGN clauses; the DISP the frontend already read stands where it does not.
     */
    @Test
    void datasetEdgesCarryTheAccessTheProgramAndTheDispState() {
        CobolSemanticModel main = programWithFiles("PGMA", List.of(
                file("INFILE", "ORDIN", FileAccess.INPUT),
                file("OUTFILE", "ORDOUT", FileAccess.OUTPUT),
                file("MASTER", "ORDMSTR", FileAccess.IO)));
        JclStep step = stepWithFacts("STEP010", "PGMA",
                List.of(dd("ORDIN", "SYKT.ORDER.DAILY"), dd("ORDOUT", "SYKW.ORDER.VALID"),
                        dd("ORDMSTR", "SYKV.ORDER.MASTER"), dd("SYSUT1", "SYKT.OTHER.DATA")),
                new JclUtilityFacts(List.of(), List.of(), List.of(), List.of(),
                        Map.of("ORDIN", DatasetAccess.UNKNOWN, "SYSUT1", DatasetAccess.READ)));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(main),
                List.of(job("JOB1", List.of(step))), List.of(), Map.of(), Map.of()));

        assertEquals("READ", edge(result, "step:JOB1.STEP010", "dataset:SYKT.ORDER.DAILY")
                .attributes().get("access"), "OPEN INPUT が DISP より優先される");
        assertEquals("WRITE", edge(result, "step:JOB1.STEP010", "dataset:SYKW.ORDER.VALID")
                .attributes().get("access"));
        assertEquals("UPDATE", edge(result, "step:JOB1.STEP010", "dataset:SYKV.ORDER.MASTER")
                .attributes().get("access"));
        assertEquals("READ", edge(result, "step:JOB1.STEP010", "dataset:SYKT.OTHER.DATA")
                .attributes().get("access"), "SELECT が指さない DD は ddRoles から取る");
    }

    /**
     * A referback the job could not resolve names no data set, so it adds neither a node nor an
     * edge: {@code DSN=*.STEP999.OUT1} where no STEP999 is written keeps its text and nothing more.
     */
    @Test
    void anUnresolvedReferbackNamesNoDataset() {
        JclDdStatement referback = new JclDdStatement("BACKREF",
                Optional.of("*.STEP999.OUT1"),
                Optional.of(new JclDataset("*.STEP999.OUT1", Optional.empty(), Optional.empty(),
                        false, Optional.of("*.STEP999.OUT1"))),
                Optional.of("SHR"), Optional.empty(), Optional.empty(), false, Map.of(),
                Map.of("DSN", "*.STEP999.OUT1"), List.of(), 0,
                SourcePosition.fileStart("JOB1.jcl"));
        JclStep step = stepWithFacts("STEP020", "PGMA",
                List.of(referback, dd("ORDIN", "SYKT.ORDER.DAILY")),
                new JclUtilityFacts(List.of(), List.of(), List.of(), List.of(),
                        Map.of("BACKREF", DatasetAccess.READ, "ORDIN", DatasetAccess.READ)));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(),
                List.of(job("JOB1", List.of(step))), List.of(), Map.of(), Map.of()));

        assertTrue(result.graph().nodes().stream()
                        .noneMatch(node -> node.id().startsWith("dataset:*.")),
                "参照解決できない referback をデータセットにしない: " + result.graph().toJson());
        assertEquals(List.of("dataset:SYKT.ORDER.DAILY"), result.graph().edges().stream()
                        .filter(edge -> edge.fromId().equals("step:JOB1.STEP020"))
                        .filter(edge -> edge.kind() == EdgeKind.REFERENCE)
                        .map(CallGraphEdge::toId).toList(),
                "解決できた DD だけが辺になる");
    }

    /** The DSN of a GDG keeps its relative generation in the node id, as the JCL writes it. */
    @Test
    void aGdgDatasetKeepsItsRelativeGenerationInTheNodeId() {
        JclStep step = stepWithFacts("STEP050", "IEBGENER",
                List.of(gdgDd("SYSUT2", "SYKT.ORDER.HISTORY", 1, "NEW")),
                new JclUtilityFacts(List.of(), List.of(), List.of(), List.of(),
                        Map.of("SYSUT2", DatasetAccess.WRITE)));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(),
                List.of(job("JOB1", List.of(step))), List.of(), Map.of(), Map.of()));

        assertEquals(NodeKind.DATASET, node(result, "dataset:SYKT.ORDER.HISTORY(+1)").kind());
        assertEquals("WRITE", edge(result, "step:JOB1.STEP050",
                "dataset:SYKT.ORDER.HISTORY(+1)").attributes().get("access"));
    }

    /** IKJEFT01 runs the program its SYSTSIN names, and the plan it runs it under is on the edge. */
    @Test
    void aProgramRunCardAddsAnExecutionEdgeCarryingItsPlanAndLauncher() {
        CobolSemanticModel batch = program("PGMD", List.of(), List.of(), List.of());
        JclStep step = stepWithFacts("STEP040", "IKJEFT01", List.of(),
                new JclUtilityFacts(
                        List.of(new JclUtilityFacts.ProgramRun("PGMD", Optional.of("SYKPLAN1"),
                                Optional.empty(), Optional.empty())),
                        List.of(), List.of(), List.of(), Map.of()));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(batch),
                List.of(job("JOB1", List.of(step))), List.of(), Map.of(), Map.of()));

        assertTrue(hasEdge(result, "step:JOB1.STEP040", "utility:IKJEFT01", EdgeKind.EXECUTION,
                Resolution.CONSTANT), "監視プログラム自体への辺は残る");
        CallGraphEdge run = edge(result, "step:JOB1.STEP040", "program:PGMD");
        assertEquals(EdgeKind.EXECUTION, run.kind());
        assertEquals(Map.of("launcher", "IKJEFT01", "plan", "SYKPLAN1"), run.attributes());
    }

    /** A BIND card names DBRM members, and a card of any utility may name data sets and tables. */
    @Test
    void bindDatasetAndTableCardsEachAddTheirOwnEdge() {
        JclStep step = stepWithFacts("STEP010", "IKJEFT01", List.of(),
                new JclUtilityFacts(List.of(),
                        List.of(new JclUtilityFacts.BindRequest("PACKAGE", "SYKPKG1",
                                List.of("PGME"), Map.of())),
                        List.of(new JclUtilityFacts.DatasetUse("SYKW.WORK.FILE",
                                DatasetAccess.DELETE)),
                        List.of(new JclUtilityFacts.TableUse("SYKDB.ZAIKOM", DatasetAccess.UPDATE)),
                        Map.of()));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(),
                List.of(job("JOB1", List.of(step))), List.of(), Map.of(), Map.of()));

        CallGraphEdge bind = edge(result, "step:JOB1.STEP010", "program:PGME");
        assertEquals(EdgeKind.REFERENCE, bind.kind());
        assertEquals(Map.of("bind", "PACKAGE", "plan", "SYKPKG1"), bind.attributes());
        assertEquals("DELETE", edge(result, "step:JOB1.STEP010", "dataset:SYKW.WORK.FILE")
                .attributes().get("access"));
        assertEquals(NodeKind.DB2_TABLE, node(result, "db2:SYKDB.ZAIKOM").kind());
        assertEquals("UPDATE", edge(result, "step:JOB1.STEP010", "db2:SYKDB.ZAIKOM")
                .attributes().get("access"));
    }

    /** Every name of utilities.txt is a leaf, so a Db2 utility is not mistaken for an application. */
    @Test
    void theUtilityListIsReadFromItsResource() {
        JclJobModel jobModel = job("JOB1", List.of(
                pgmStep("JOB1", "STEP010", "DSNTIAUL", List.of()),
                pgmStep("JOB1", "STEP020", "IEHPROGM", List.of()),
                pgmStep("JOB1", "STEP030", "ICETOOL", List.of())));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(), List.of(jobModel),
                List.of(), Map.of(), Map.of()));

        for (String utility : List.of("DSNTIAUL", "IEHPROGM", "ICETOOL")) {
            assertEquals(NodeKind.EXTERNAL_UTILITY, node(result, "utility:" + utility).kind());
        }
    }

    /** Two OPENs of one file, one for input and one for output, amount to an update of it. */
    @Test
    void aFileOpenedForInputAndForOutputIsAnUpdate() {
        CobolSemanticModel main = programWithFiles("PGMA", List.of(
                file("WORKFILE", "ORDWORK", FileAccess.INPUT, FileAccess.OUTPUT)));
        JclStep step = stepWithFacts("STEP010", "PGMA",
                List.of(dd("ORDWORK", "SYKW.ORDER.WORK")),
                new JclUtilityFacts(List.of(), List.of(), List.of(), List.of(), Map.of()));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(main),
                List.of(job("JOB1", List.of(step))), List.of(), Map.of(), Map.of()));

        assertEquals("UPDATE", edge(result, "step:JOB1.STEP010", "dataset:SYKW.ORDER.WORK")
                .attributes().get("access"));
    }

    /** Two SELECT entries may name one DD; the modes of both decide what the step does with it. */
    @Test
    void twoSelectEntriesNamingOneDdAreTakenTogether() {
        CobolSemanticModel main = programWithFiles("PGMA", List.of(
                file("READSIDE", "ORDWORK", FileAccess.INPUT),
                file("WRITESIDE", "ORDWORK", FileAccess.OUTPUT)));
        JclStep step = stepWithFacts("STEP010", "PGMA",
                List.of(dd("ORDWORK", "SYKW.ORDER.WORK")),
                new JclUtilityFacts(List.of(), List.of(), List.of(), List.of(), Map.of()));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(main),
                List.of(job("JOB1", List.of(step))), List.of(), Map.of(), Map.of()));

        assertEquals("UPDATE", edge(result, "step:JOB1.STEP010", "dataset:SYKW.ORDER.WORK")
                .attributes().get("access"));
    }

    /**
     * One data set reached through two DD statements of a step, read through one and written
     * through the other, is an update of it; neither role is lost with the edge that carried it.
     */
    @Test
    void readingThroughOneDdAndWritingThroughAnotherIsAnUpdate() {
        CobolSemanticModel main = programWithFiles("PGMA", List.of(
                file("INFILE", "ORDIN", FileAccess.INPUT),
                file("OUTFILE", "ORDOUT", FileAccess.OUTPUT)));
        JclStep step = stepWithFacts("STEP010", "PGMA",
                List.of(dd("ORDIN", "SYKW.ORDER.WORK"), dd("ORDOUT", "SYKW.ORDER.WORK")),
                new JclUtilityFacts(List.of(), List.of(), List.of(), List.of(), Map.of()));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(main),
                List.of(job("JOB1", List.of(step))), List.of(), Map.of(), Map.of()));

        assertEquals("UPDATE", edge(result, "step:JOB1.STEP010", "dataset:SYKW.ORDER.WORK")
                .attributes().get("access"));
    }

    /**
     * A data set a step already updates stays updated whatever the second DD says about it: an
     * update read beside an update written is an update either way round.
     */
    @Test
    void anUpdateOnOneSideKeepsThePairAnUpdate() {
        CobolSemanticModel main = programWithFiles("PGMA", List.of(
                file("IOFILE", "ORDIO", FileAccess.IO),
                file("INFILE", "ORDIN", FileAccess.INPUT)));
        JclStep step = stepWithFacts("STEP010", "PGMA",
                List.of(dd("ORDIO", "SYKW.ORDER.WORK"), dd("ORDIN", "SYKW.ORDER.WORK")),
                new JclUtilityFacts(List.of(), List.of(), List.of(), List.of(), Map.of()));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(main),
                List.of(job("JOB1", List.of(step))), List.of(), Map.of(), Map.of()));

        assertEquals("UPDATE", edge(result, "step:JOB1.STEP010", "dataset:SYKW.ORDER.WORK")
                .attributes().get("access"), "更新と参照を合わせても更新のままであること");
    }

    /**
     * Any other pair keeps what the step states first. A data set one DD of a step deletes and
     * another reads is still deleted: nothing says the two describe one use, and the step writes
     * the delete first.
     */
    @Test
    void aPairThatIsNoUpdateKeepsTheAccessStatedFirst() {
        JclStep step = stepWithFacts("STEP010", "IDCAMS",
                List.of(dd("ORDDEL", "SYKT.ORDER.DAILY"), dd("ORDIN", "SYKT.ORDER.DAILY")),
                new JclUtilityFacts(List.of(), List.of(), List.of(), List.of(),
                        Map.of("ORDDEL", DatasetAccess.DELETE, "ORDIN", DatasetAccess.READ)));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(),
                List.of(job("JOB1", List.of(step))), List.of(), Map.of(), Map.of()));

        assertEquals("DELETE", edge(result, "step:JOB1.STEP010", "dataset:SYKT.ORDER.DAILY")
                .attributes().get("access"), "先に述べた削除が残ること");
    }

    /** A utility named on a RUN PROGRAM card is a utility, exactly as an EXEC PGM= naming it is. */
    @Test
    void aRunProgramCardNamingAUtilityTypesItAsOne() {
        JclStep step = stepWithFacts("STEP010", "IKJEFT01", List.of(),
                new JclUtilityFacts(
                        List.of(new JclUtilityFacts.ProgramRun("DSNTEP2", Optional.of("DSNTEP2"),
                                Optional.empty(), Optional.empty())),
                        List.of(), List.of(), List.of(), Map.of()));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(),
                List.of(job("JOB1", List.of(step))), List.of(), Map.of(), Map.of()));

        assertEquals(NodeKind.EXTERNAL_UTILITY, node(result, "utility:DSNTEP2").kind());
        assertTrue(result.graph().nodes().stream()
                        .noneMatch(n -> n.id().equals("program:DSNTEP2")),
                "外部プログラム扱いのノードを作らない: " + result.graph().toJson());
        assertTrue(hasEdge(result, "step:JOB1.STEP010", "utility:DSNTEP2", EdgeKind.EXECUTION,
                Resolution.CONSTANT));
    }

    /** One step may bind one member twice, as a package and as a plan; both reasons stay on the edge. */
    @Test
    void bindingOneMemberTwiceKeepsBothKindsAndPlans() {
        JclStep step = stepWithFacts("STEP010", "IKJEFT01", List.of(),
                new JclUtilityFacts(List.of(),
                        List.of(new JclUtilityFacts.BindRequest("PACKAGE", "SYKPKG1",
                                        List.of("PGME"), Map.of()),
                                new JclUtilityFacts.BindRequest("PLAN", "SYKPLAN1",
                                        List.of("PGME"), Map.of())),
                        List.of(), List.of(), Map.of()));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(),
                List.of(job("JOB1", List.of(step))), List.of(), Map.of(), Map.of()));

        CallGraphEdge bind = edge(result, "step:JOB1.STEP010", "program:PGME");
        assertEquals(Map.of("bind", "PACKAGE,PLAN", "plan", "SYKPKG1,SYKPLAN1"), bind.attributes());
    }

    /**
     * A launcher reads none of the step's DD statements; the program its card runs does, so that
     * program's FILE-CONTROL is what decides the access.
     */
    @Test
    void aLauncherStepTakesItsDdAccessFromTheProgramItRuns() {
        CobolSemanticModel batch = programWithFiles("PGMD", List.of(
                file("INFILE", "ORDIN", FileAccess.INPUT)));
        JclStep step = stepWithFacts("STEP040", "IKJEFT01",
                List.of(dd("ORDIN", "SYKT.ORDER.DAILY")),
                new JclUtilityFacts(
                        List.of(new JclUtilityFacts.ProgramRun("PGMD", Optional.of("SYKPLAN1"),
                                Optional.empty(), Optional.empty())),
                        List.of(), List.of(), List.of(), Map.of()));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(batch),
                List.of(job("JOB1", List.of(step))), List.of(), Map.of(), Map.of()));

        assertEquals("READ", edge(result, "step:JOB1.STEP040", "dataset:SYKT.ORDER.DAILY")
                .attributes().get("access"));
    }

    // ---- embedded SQL: CRUD letters and a stored procedure call ----

    /** The letters of every statement of one program are gathered onto its table edge, R C U D. */
    @Test
    void aDb2TableEdgeCarriesTheCrudLettersOfTheWholeProgram() {
        SqlStatementModel select = sql(SqlStatementKind.SELECT, "SELECT A FROM SYKDB.ZAIKOM", 200,
                builder -> builder.referencedTables(List.of("SYKDB.ZAIKOM"))
                        .tableAccess(Map.of("SYKDB.ZAIKOM", "R")));
        SqlStatementModel update = sql(SqlStatementKind.UPDATE, "UPDATE SYKDB.ZAIKOM SET A = 1", 210,
                builder -> builder.referencedTables(List.of("SYKDB.ZAIKOM"))
                        .tableAccess(Map.of("SYKDB.ZAIKOM", "U")));
        CobolSemanticModel pgm = program("PGMF", List.of(), List.of(), List.of());
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(pgm), List.of(),
                List.of(), Map.of("PGMF", List.of(select, update)), Map.of()));

        assertEquals("RU", edge(result, "program:PGMF", "db2:SYKDB.ZAIKOM")
                .attributes().get("access"));
    }

    /** A stored procedure is a callee like any other; one outside the folder is external. */
    @Test
    void anSqlCallAddsACallEdgeToTheProcedure() {
        SqlStatementModel call = sql(SqlStatementKind.OTHER, "CALL SYKPROC1", 220,
                builder -> builder.procedureName("SYKPROC1"));
        CobolSemanticModel pgm = program("PGMG", List.of(), List.of(), List.of());
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(pgm), List.of(),
                List.of(), Map.of("PGMG", List.of(call)), Map.of()));

        CallGraphEdge edge = edge(result, "program:PGMG", "program:SYKPROC1");
        assertEquals(EdgeKind.CALL, edge.kind());
        assertEquals("true", edge.attributes().get("sqlProcedure"));
        assertEquals("true", node(result, "program:SYKPROC1").attributes().get("external"));
    }

    // ---- SQL scripts ----

    /**
     * A CALL reaches the routine a script defines even when the two spell the qualifier
     * differently, because the name without the schema is what they agree on. The routine is then
     * a program of the folder rather than an external one.
     */
    @Test
    void anSqlCallReachesTheRoutineAScriptDefines() {
        SqlStatementModel call = sql(SqlStatementKind.CALL, "CALL SYKDB.SYKPROC2", 240,
                builder -> builder.procedureName("SYKDB.SYKPROC2"));
        SqlStatementModel update = sql(SqlStatementKind.UPDATE, "UPDATE SYKDB.ZAIKOM SET C = 1", 1,
                builder -> builder.referencedTables(List.of("SYKDB.ZAIKOM"))
                        .tableAccess(Map.of("SYKDB.ZAIKOM", "U")));
        SqlRoutineDefinition routine = new SqlRoutineDefinition("CSDB.SYKPROC2", "ddl/SYK.sql", 12,
                List.of(update));
        CobolSemanticModel pgm = program("PGMH", List.of(), List.of(), List.of());
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(pgm), List.of(), List.of(),
                Map.of("PGMH", List.of(call)), Map.of(), Map.of(), List.of(routine)));

        assertEquals(EdgeKind.CALL, edge(result, "program:PGMH", "sqlroutine:SYKPROC2").kind());
        CallGraphNode node = node(result, "sqlroutine:SYKPROC2");
        assertEquals(NodeKind.PROGRAM, node.kind());
        assertEquals("true", node.attributes().get("sqlProcedure"));
        assertEquals("ddl/SYK.sql", node.attributes().get("definedIn"));
        assertNull(node.attributes().get("external"), "フォルダー内で定義した手続きは外部ではないこと");
        CallGraphEdge reference = edge(result, "sqlroutine:SYKPROC2", "db2:SYKDB.ZAIKOM");
        assertEquals(EdgeKind.REFERENCE, reference.kind());
        assertEquals("U", reference.attributes().get("access"));
        assertEquals(1, reference.line(), "辺の行は本体の文が立つ行であること");
    }

    /**
     * A routine keeps its own id space. A COBOL program of the same name is a different thing, and
     * merging the two would show a call reaching code that program never held.
     */
    @Test
    void aRoutineDoesNotMergeIntoTheProgramOfTheSameName() {
        CobolSemanticModel pgm = program("SYKPROC3", List.of(), List.of(), List.of());
        SqlRoutineDefinition routine = new SqlRoutineDefinition("CSDB.SYKPROC3", "ddl/SYK.sql", 3,
                List.of());
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(pgm), List.of(), List.of(),
                Map.of(), Map.of(), Map.of(), List.of(routine)));

        assertEquals(NodeKind.PROGRAM, node(result, "program:SYKPROC3").kind());
        assertNull(node(result, "program:SYKPROC3").attributes().get("sqlProcedure"),
                "COBOL プログラムのノードは手続きの属性を持たないこと");
        assertEquals("ddl/SYK.sql",
                node(result, "sqlroutine:SYKPROC3").attributes().get("definedIn"));
    }

    /** Two scripts declaring one routine name make one node that names both of them. */
    @Test
    void aRoutineDeclaredTwiceNamesEveryScriptThatDeclaresIt() {
        SqlRoutineDefinition first = new SqlRoutineDefinition("CSDB.SYKPROC4", "ddl/A.sql", 3,
                List.of());
        SqlRoutineDefinition second = new SqlRoutineDefinition("FLDB.SYKPROC4", "ddl/B.sql", 7,
                List.of());
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(), List.of(), List.of(),
                Map.of(), Map.of(), Map.of(), List.of(first, second)));

        assertEquals("ddl/A.sql,ddl/B.sql",
                node(result, "sqlroutine:SYKPROC4").attributes().get("definedIn"));
    }

    /** A CREATE TABLE names the table, so the node says how many columns and which script. */
    @Test
    void aDeclaredTableCarriesItsColumnCount() {
        SqlStatementModel create = sql(SqlStatementKind.DDL,
                "CREATE TABLE SYKDB.ZAIKOM (ZAIKO_CD CHAR(8) NOT NULL, ZAIKO_SU INTEGER)", 5,
                builder -> builder.declaredTable("SYKDB.ZAIKOM")
                        .declaredColumns(List.of(
                                new SqlDeclaredColumn("ZAIKO_CD", "CHAR(8)", false),
                                new SqlDeclaredColumn("ZAIKO_SU", "INTEGER", true))));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(), List.of(), List.of(),
                Map.of(), Map.of(), Map.of("ddl/ZAIKOM.sql", List.of(create)), List.of()));

        CallGraphNode node = node(result, "db2:SYKDB.ZAIKOM");
        assertEquals(NodeKind.DB2_TABLE, node.kind());
        assertEquals("2", node.attributes().get("columns"));
        assertEquals("ddl/ZAIKOM.sql", node.attributes().get("definedIn"));
        assertEquals(List.of(), result.graph().edges(), "宣言だけでは辺を作らないこと");
    }

    // ---- determinism ----

    @Test
    void producesIdenticalJsonAndDotForSameInput() {
        Statement move = stmt("MOVE", "MOVE 'PGMB' TO WS-PROG-NAME", "PGMA.cbl", 70);
        CallRelation dynamicCall = new CallRelation("PGMA", CallKind.DYNAMIC, "WS-PROG-NAME",
                range("PGMA.cbl", 113));
        CobolSemanticModel caller = program("PGMA", List.of(move), List.of(dynamicCall),
                List.of(cics(EmbeddedBlockKind.CICS_RETURN_TRANSID, Map.of("TRANSID", "SYK8"),
                        "PGMA.cbl", 66)));
        JclJobModel jobModel = job("JOB1", List.of(pgmStep("JOB1", "STEP010", "PGMA",
                List.of(dd("IN1", "SYKT.INPUT.DATA")))));
        LinkerInput input = new LinkerInput(List.of(caller), List.of(jobModel), List.of(),
                Map.of(), Map.of("SYK8", "PGMA"));

        LinkResult first = CallGraphLinker.link(input);
        LinkResult second = CallGraphLinker.link(input);
        assertEquals(first.graph().toJson(), second.graph().toJson());
        assertEquals(first.graph().toDot(), second.graph().toDot());
        assertEquals(first.findings(), second.findings());
    }
}
