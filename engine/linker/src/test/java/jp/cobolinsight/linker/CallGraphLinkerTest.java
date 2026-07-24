package jp.cobolinsight.linker;

import jp.cobolinsight.engineapi.bms.BmsField;
import jp.cobolinsight.engineapi.bms.BmsMap;
import jp.cobolinsight.engineapi.bms.BmsMapset;
import jp.cobolinsight.engineapi.callgraph.CallGraphEdge;
import jp.cobolinsight.engineapi.callgraph.CallGraphNode;
import jp.cobolinsight.engineapi.callgraph.EdgeKind;
import jp.cobolinsight.engineapi.callgraph.NodeKind;
import jp.cobolinsight.engineapi.callgraph.Resolution;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.jcl.JclDdStatement;
import jp.cobolinsight.engineapi.jcl.JclExecKind;
import jp.cobolinsight.engineapi.jcl.JclJobModel;
import jp.cobolinsight.engineapi.jcl.JclStep;
import jp.cobolinsight.engineapi.semantic.CallKind;
import jp.cobolinsight.engineapi.semantic.CallRelation;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.CompoundStatement;
import jp.cobolinsight.engineapi.semantic.ControlKind;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlock;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlockKind;
import jp.cobolinsight.engineapi.semantic.Procedure;
import jp.cobolinsight.engineapi.semantic.ProcedureKind;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.semantic.StatementBlock;
import jp.cobolinsight.engineapi.sql.SqlStatementKind;
import jp.cobolinsight.engineapi.sql.SqlStatementModel;
import jp.cobolinsight.engineapi.sql.SqlStructureSignals;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.source.SourceRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                embeddedBlocks, List.of());
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
        return new JclDdStatement(ddName, Optional.ofNullable(dsn),
                SourcePosition.fileStart("JOB1.jcl"));
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

    // ---- JCL: EXEC PGM=、データセット参照、外部ユーティリティ ----

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
        // STEPLIB(ロードライブラリ)とDSN無しDDはデータセット辺にしない
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

    // ---- CALL: 静的・動的(定数伝播)・未解決 ----

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
        // 過大近似の仕様固定: 分岐のTHEN/ELSE双方のMOVEを実行順序・分岐条件と無関係に候補とする
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

    // ---- EXEC CICS: 遷移辺・マップ参照辺・トランザクション解決 ----

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
    void linksCicsLinkAndStart() {
        CobolSemanticModel pgm1 = program("PGM1", List.of(), List.of(), List.of(
                cics(EmbeddedBlockKind.CICS_LINK, Map.of("PROGRAM", "PGM2"), "PGM1.cbl", 20),
                cics(EmbeddedBlockKind.CICS_START, Map.of("TRANSID", "SYK9"), "PGM1.cbl", 30)));
        LinkResult result = CallGraphLinker.link(new LinkerInput(List.of(pgm1), List.of(),
                List.of(), Map.of(), Map.of()));

        assertTrue(hasEdge(result, "program:PGM1", "program:PGM2",
                EdgeKind.TRANSACTION_TRANSITION, Resolution.CONSTANT), "LINK遷移辺");
        assertTrue(hasEdge(result, "program:PGM1", "transaction:SYK9",
                EdgeKind.TRANSACTION_TRANSITION, Resolution.CONSTANT), "START TRANSID遷移辺");
        // 定義表に無いトランザクションはプログラムへの解決辺を持たない葉として残る
        assertTrue(result.graph().edges().stream()
                .noneMatch(e -> e.fromId().equals("transaction:SYK9")));
        assertTrue(result.findings().stream().anyMatch(f ->
                f.ruleId().equals(CallGraphLinker.TRANSACTION_UNRESOLVED_RULE_ID)
                        && f.level() == FindingLevel.NOTE));
    }

    // ---- Db2表参照 ----

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

    // ---- 決定論 ----

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
