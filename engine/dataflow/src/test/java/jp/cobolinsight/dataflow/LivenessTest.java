package jp.cobolinsight.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.dataflow.ProgramDataFlow;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 生存解析(後進): IN=(OUT-def)∪use の基本ケースを liveOut で検証する。 */
class LivenessTest {

    private static final String SRC = InlinePrograms.source(
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. LIVEP.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-A  PIC 9(04).",
            "       01  WS-B  PIC 9(04).",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           MOVE 1 TO WS-A.",
            "           MOVE WS-A TO WS-B.",
            "           DISPLAY WS-B.",
            "           STOP RUN.");

    @Test
    void variableIsLiveUntilItsLastUse() {
        CobolSemanticModel model = InlinePrograms.parse(SRC);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = DataFlowEngine.analyze(model, cfg);

        CfgNode moveLiteral = InlinePrograms.nodeContaining(cfg, "MOVE 1 TO WS-A");
        CfgNode moveAtoB = InlinePrograms.nodeContaining(cfg, "MOVE WS-A TO WS-B");
        CfgNode display = InlinePrograms.nodeContaining(cfg, "DISPLAY WS-B");

        assertTrue(df.liveOut(moveLiteral).contains("WS-A"),
                "WS-A は後続で使われるため MOVE 1 TO WS-A の出口で生存");
        assertFalse(df.liveOut(moveLiteral).contains("WS-B"),
                "WS-B はまだ生存していない");

        assertTrue(df.liveOut(moveAtoB).contains("WS-B"),
                "WS-B は DISPLAY で使われるため生存");
        assertFalse(df.liveOut(moveAtoB).contains("WS-A"),
                "WS-A は再使用されず MOVE WS-A TO WS-B の出口で死んでいる");

        assertFalse(df.liveOut(display).contains("WS-B"),
                "DISPLAY WS-B の出口では WS-B は生存しない");
    }
}
