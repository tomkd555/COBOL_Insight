package jp.cobolinsight.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.dataflow.ProgramDataFlow;
import jp.cobolinsight.engineapi.dataflow.TaintKind;
import jp.cobolinsight.engineapi.dataflow.TaintStep;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;


/** 汚染伝播経路: 汚染源から問い合わせノードまでの代入の連なりを検証する。 */
class TaintPathTest {

    private static final String SRC = InlinePrograms.source(
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. TPATH.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-IN       PIC X(10).",
            "       01  WS-MID      PIC X(10).",
            "       01  WS-OUT      PIC X(10).",
            "       01  WS-CLEAN    PIC X(10) VALUE SPACES.",
            "       01  WS-CARD-NO  PIC X(16).",
            "       01  WS-TMP      PIC X(16).",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           ACCEPT WS-IN.",
            "           MOVE WS-IN TO WS-MID.",
            "           MOVE WS-MID TO WS-OUT.",
            "           MOVE SPACES TO WS-CLEAN.",
            "           MOVE WS-CARD-NO TO WS-TMP.",
            "           DISPLAY WS-OUT.",
            "           DISPLAY WS-TMP.",
            "           STOP RUN.");

    private record Hop(int line, String variable, String from) {
    }

    private static List<Hop> hops(List<TaintStep> path) {
        return path.stream()
                .map(step -> new Hop(
                        ((SimpleStatement) step.node().statement().orElseThrow()).range().start()
                                .line(),
                        step.variable(), step.from().orElse(null)))
                .toList();
    }

    private static ProgramDataFlow analyze(ControlFlowGraph cfg, CobolSemanticModel model) {
        return DataFlowEngine.analyze(model, cfg);
    }

    @Test
    void externalInputPathRecordsSourceThenEachAssignment() {
        CobolSemanticModel model = InlinePrograms.parse(SRC);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = analyze(cfg, model);

        CfgNode displayOut = InlinePrograms.nodeContaining(cfg, "DISPLAY WS-OUT");
        List<TaintStep> path = df.taintPathTo(displayOut, "WS-OUT", TaintKind.EXTERNAL_INPUT);

        assertEquals(List.of(
                        new Hop(13, "WS-IN", null),
                        new Hop(14, "WS-MID", "WS-IN"),
                        new Hop(15, "WS-OUT", "WS-MID")),
                hops(path),
                "ACCEPT(汚染源)→MOVE→MOVE の順に経路を保持すること");
    }

    @Test
    void sensitivePathStartsAtTheFirstAssignmentFromTheDeclaredItem() {
        CobolSemanticModel model = InlinePrograms.parse(SRC);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = analyze(cfg, model);

        CfgNode displayTmp = InlinePrograms.nodeContaining(cfg, "DISPLAY WS-TMP");
        List<TaintStep> path = df.taintPathTo(displayTmp, "WS-TMP", TaintKind.SENSITIVE);

        assertEquals(List.of(new Hop(17, "WS-TMP", "WS-CARD-NO")), hops(path),
                "機密名義の宣言は文を持たないため、最初の代入から経路が始まること");
    }

    @Test
    void declaredSensitiveItemItselfHasNoStatementPath() {
        CobolSemanticModel model = InlinePrograms.parse(SRC);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = analyze(cfg, model);

        CfgNode displayTmp = InlinePrograms.nodeContaining(cfg, "DISPLAY WS-TMP");

        assertEquals(List.of(), df.taintPathTo(displayTmp, "WS-CARD-NO", TaintKind.SENSITIVE),
                "宣言由来の汚染だけを持つ変数の経路は空になること");
    }

    @Test
    void untaintedVariableHasEmptyPath() {
        CobolSemanticModel model = InlinePrograms.parse(SRC);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = analyze(cfg, model);

        CfgNode displayOut = InlinePrograms.nodeContaining(cfg, "DISPLAY WS-OUT");

        assertEquals(List.of(), df.taintPathTo(displayOut, "WS-CLEAN", TaintKind.EXTERNAL_INPUT),
                "汚染下でない変数の経路は空になること");
    }

    @Test
    void pathIsLowerCaseInsensitiveAndDeterministic() {
        CobolSemanticModel model = InlinePrograms.parse(SRC);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = analyze(cfg, model);

        CfgNode displayOut = InlinePrograms.nodeContaining(cfg, "DISPLAY WS-OUT");
        List<TaintStep> lower = df.taintPathTo(displayOut, "ws-out", TaintKind.EXTERNAL_INPUT);
        List<TaintStep> upper = df.taintPathTo(displayOut, "WS-OUT", TaintKind.EXTERNAL_INPUT);

        assertEquals(hops(upper), hops(lower), "変数名は正規化して照合すること");
        assertEquals(hops(upper), hops(df.taintPathTo(displayOut, "WS-OUT",
                TaintKind.EXTERNAL_INPUT)), "同一問い合わせは同一経路を返すこと");
    }

    @Test
    void pathTerminatesOnLoopCarriedTaint() {
        String loopSrc = InlinePrograms.source(
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. TLOOP.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-IN       PIC X(10).",
                "       01  WS-A        PIC X(10).",
                "       01  WS-B        PIC X(10).",
                "       01  WS-I        PIC 9(02) VALUE 0.",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           ACCEPT WS-IN.",
                "           MOVE WS-IN TO WS-A.",
                "           PERFORM UNTIL WS-I > 3",
                "               MOVE WS-A TO WS-B",
                "               MOVE WS-B TO WS-A",
                "               ADD 1 TO WS-I",
                "           END-PERFORM.",
                "           DISPLAY WS-A.",
                "           STOP RUN.");
        CobolSemanticModel model = InlinePrograms.parse(loopSrc);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = analyze(cfg, model);

        CfgNode displayA = InlinePrograms.nodeContaining(cfg, "DISPLAY WS-A");
        List<TaintStep> path = df.taintPathTo(displayA, "WS-A", TaintKind.EXTERNAL_INPUT);

        assertEquals(List.of(new Hop(11, "WS-IN", null), new Hop(12, "WS-A", "WS-IN")), hops(path),
                "ループで往復する汚染でも、汚染源に根を持つ最短経路を返して有限で終わること");
        assertEquals(Optional.empty(), path.get(0).from(),
                "経路の先頭は汚染源(伝播元を持たない)であること");
    }
}
