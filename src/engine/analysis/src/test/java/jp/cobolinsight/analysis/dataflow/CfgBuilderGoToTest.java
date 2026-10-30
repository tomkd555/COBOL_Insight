package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the edges of a GO TO ... DEPENDING ON, written as a statement and in a phrase. */
class CfgBuilderGoToTest {

    private static final String SOURCE = InlinePrograms.source(
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. DEPPGM.",
            "       ENVIRONMENT DIVISION.",
            "       INPUT-OUTPUT SECTION.",
            "       FILE-CONTROL.",
            "           SELECT F1 ASSIGN TO F1.",
            "       DATA DIVISION.",
            "       FILE SECTION.",
            "       FD  F1.",
            "       01  F1-REC       PIC X(10).",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-X         PIC 9.",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           READ F1 AT END GO TO A-PARA B-PARA DEPENDING ON WS-X",
            "           END-READ",
            "           GO TO A-PARA DEPENDING ON WS-X.",
            "       NEXT-PARA.",
            "           DISPLAY 'NEXT'",
            "           STOP RUN.",
            "       A-PARA.",
            "           DISPLAY 'A'",
            "           STOP RUN.",
            "       B-PARA.",
            "           DISPLAY 'B'",
            "           STOP RUN.");

    /** With no target matching, control goes on to the next paragraph. */
    @Test
    void aGoToDependingOnWithOneTargetFallsThrough() {
        ControlFlowGraph cfg = InlinePrograms.cfg(SOURCE);
        CfgNode goTo = cfg.nodes().stream()
                .filter(node -> node.statement().isPresent()
                        && node.statement().orElseThrow().range().start().line() == 17)
                .findFirst().orElseThrow();

        assertTrue(cfg.successors(goTo).contains(InlinePrograms.nodeContaining(cfg, "'A'")));
        assertTrue(cfg.successors(goTo).contains(InlinePrograms.nodeContaining(cfg, "'NEXT'")));
    }

    @Test
    void aGoToDependingOnInAPhraseBranchesToEveryTarget() {
        ControlFlowGraph cfg = InlinePrograms.cfg(SOURCE);
        CfgNode read = InlinePrograms.nodeContaining(cfg, "READ F1");

        assertTrue(cfg.successors(read).contains(InlinePrograms.nodeContaining(cfg, "'A'")));
        assertTrue(cfg.successors(read).contains(InlinePrograms.nodeContaining(cfg, "'B'")));
        assertFalse(cfg.successors(read).contains(InlinePrograms.nodeContaining(cfg, "'NEXT'")));
    }
}
