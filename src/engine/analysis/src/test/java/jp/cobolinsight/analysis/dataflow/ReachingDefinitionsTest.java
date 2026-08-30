package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.dataflow.ProgramDataFlow;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reaching-definitions analysis: verifies detecting uninitialized use of an item without VALUE, and not detecting it after a definition or when VALUE is present. */
class ReachingDefinitionsTest {

    private static final String SRC = InlinePrograms.source(
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. UNINIT.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-A  PIC 9(04).",
            "       01  WS-B  PIC 9(04) VALUE ZERO.",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           DISPLAY WS-A.",
            "           MOVE 10 TO WS-A.",
            "           DISPLAY WS-A.",
            "           DISPLAY WS-B.",
            "           STOP RUN.");

    @Test
    void detectsUseBeforeDefinitionAndClearsAfter() {
        CobolSemanticModel model = InlinePrograms.parse(SRC);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = DataFlowEngine.analyze(model, cfg);

        List<CfgNode> displaysA = InlinePrograms.nodesContaining(cfg, "DISPLAY WS-A");
        assertTrue(displaysA.size() == 2, "DISPLAY WS-A が2つあること");
        CfgNode beforeMove = displaysA.get(0);
        CfgNode afterMove = displaysA.get(1);

        assertTrue(df.mayReachUninitialized(beforeMove, "WS-A"),
                "MOVE 前の DISPLAY WS-A は未初期化到達を検出する");
        assertFalse(df.mayReachUninitialized(afterMove, "WS-A"),
                "MOVE 後の DISPLAY WS-A は未初期化到達を検出しない");
    }

    @Test
    void valueClauseSuppressesUninitialized() {
        CobolSemanticModel model = InlinePrograms.parse(SRC);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = DataFlowEngine.analyze(model, cfg);

        CfgNode displayB = InlinePrograms.nodeContaining(cfg, "DISPLAY WS-B");
        assertFalse(df.mayReachUninitialized(displayB, "WS-B"),
                "VALUE 句を持つ WS-B は未初期化到達を検出しない");
    }

    @Test
    void queryIsCaseInsensitive() {
        CobolSemanticModel model = InlinePrograms.parse(SRC);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = DataFlowEngine.analyze(model, cfg);

        CfgNode beforeMove = InlinePrograms.nodesContaining(cfg, "DISPLAY WS-A").get(0);
        assertTrue(df.mayReachUninitialized(beforeMove, "ws-a"),
                "変数名は正規化して照合する");
    }

    @Test
    void defsAndUsesArePopulated() {
        CobolSemanticModel model = InlinePrograms.parse(SRC);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = DataFlowEngine.analyze(model, cfg);

        CfgNode move = InlinePrograms.nodeContaining(cfg, "MOVE 10 TO WS-A");
        assertTrue(df.defsAt(move).contains("WS-A"));
        CfgNode displayA = InlinePrograms.nodesContaining(cfg, "DISPLAY WS-A").get(0);
        assertTrue(df.usesAt(displayA).contains("WS-A"));
    }
}
