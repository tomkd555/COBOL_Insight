package jp.cobolinsight.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.dataflow.ProgramDataFlow;
import jp.cobolinsight.engineapi.dataflow.TaintKind;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 汚染追跡: 外部入力(ACCEPT)と機密名義の source→dest 伝播を検証する。 */
class TaintAnalysisTest {

    private static final String SRC = InlinePrograms.source(
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. TAINTP.",
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

    @Test
    void externalInputPropagatesThroughMoves() {
        CobolSemanticModel model = InlinePrograms.parse(SRC);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = DataFlowEngine.analyze(model, cfg);

        CfgNode displayOut = InlinePrograms.nodeContaining(cfg, "DISPLAY WS-OUT");
        Set<String> tainted = df.taintedAt(displayOut, TaintKind.EXTERNAL_INPUT);
        assertTrue(tainted.contains("WS-IN"), "ACCEPT 受信先 WS-IN が汚染源");
        assertTrue(tainted.contains("WS-MID"), "WS-IN からの伝播で WS-MID が汚染");
        assertTrue(tainted.contains("WS-OUT"), "WS-MID からの伝播で WS-OUT が汚染");
        assertFalse(tainted.contains("WS-CLEAN"), "リテラル代入の WS-CLEAN は汚染されない");
    }

    @Test
    void sensitiveNameIsTaintedAndPropagates() {
        CobolSemanticModel model = InlinePrograms.parse(SRC);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = DataFlowEngine.analyze(model, cfg);

        CfgNode displayTmp = InlinePrograms.nodeContaining(cfg, "DISPLAY WS-TMP");
        Set<String> sensitive = df.taintedAt(displayTmp, TaintKind.SENSITIVE);
        assertTrue(sensitive.contains("WS-CARD-NO"), "名称末尾 -CARD-NO は機密汚染源");
        assertTrue(sensitive.contains("WS-TMP"), "機密変数からの伝播で WS-TMP が汚染");
    }

    @Test
    void sensitiveTaintPresentFromEntry() {
        CobolSemanticModel model = InlinePrograms.parse(SRC);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = DataFlowEngine.analyze(model, cfg);

        CfgNode accept = InlinePrograms.nodeContaining(cfg, "ACCEPT WS-IN");
        assertTrue(df.taintedAt(accept, TaintKind.SENSITIVE).contains("WS-CARD-NO"),
                "機密名義の恒常汚染は先頭ノードでも成立する");
    }

    @Test
    void externalAndSensitiveAreSeparateKinds() {
        CobolSemanticModel model = InlinePrograms.parse(SRC);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = DataFlowEngine.analyze(model, cfg);

        CfgNode displayOut = InlinePrograms.nodeContaining(cfg, "DISPLAY WS-OUT");
        assertFalse(df.taintedAt(displayOut, TaintKind.EXTERNAL_INPUT).contains("WS-CARD-NO"),
                "機密汚染は EXTERNAL_INPUT の集合には現れない");
    }
}
