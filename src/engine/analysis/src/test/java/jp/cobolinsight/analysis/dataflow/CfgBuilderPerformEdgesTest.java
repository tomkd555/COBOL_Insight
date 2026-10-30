package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.PerformRelation;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.ProcedureKind;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the call and return edges of a PERFORM written in a phrase of another statement, of a
 * PERFORM that a COPY member included twice brings into two paragraphs, and of a performed
 * section.
 */
class CfgBuilderPerformEdgesTest {

    private static final String PHRASES = InlinePrograms.source(
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. PHRPGM.",
            "       ENVIRONMENT DIVISION.",
            "       INPUT-OUTPUT SECTION.",
            "       FILE-CONTROL.",
            "           SELECT F1 ASSIGN TO F1.",
            "       DATA DIVISION.",
            "       FILE SECTION.",
            "       FD  F1.",
            "       01  F1-REC       PIC X(10).",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-A         PIC 9(3).",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           READ F1 AT END PERFORM EOF-PARA END-READ",
            "           CALL 'SUB' ON EXCEPTION PERFORM ERR-PARA END-CALL",
            "           COMPUTE WS-A = WS-A + 1",
            "               ON SIZE ERROR PERFORM ERR-PARA",
            "           END-COMPUTE",
            "           DISPLAY 'AFTER'",
            "           STOP RUN.",
            "       EOF-PARA.",
            "           DISPLAY 'EOF'.",
            "       ERR-PARA.",
            "           DISPLAY 'ERR'.");

    @Test
    void aPerformInAPhraseCallsTheParagraphAndReturnsAfterTheStatement() {
        ControlFlowGraph cfg = InlinePrograms.cfg(PHRASES);
        CfgNode read = InlinePrograms.nodeContaining(cfg, "READ F1");
        CfgNode call = InlinePrograms.nodeContaining(cfg, "CALL 'SUB'");
        CfgNode compute = InlinePrograms.nodeContaining(cfg, "COMPUTE");
        CfgNode after = InlinePrograms.nodeContaining(cfg, "'AFTER'");
        CfgNode eof = InlinePrograms.nodeContaining(cfg, "'EOF'");
        CfgNode err = InlinePrograms.nodeContaining(cfg, "'ERR'");

        assertTrue(cfg.successors(read).contains(eof), "AT END PERFORM calls EOF-PARA");
        assertTrue(cfg.successors(eof).contains(call), "and returns after the READ");
        assertTrue(cfg.successors(call).contains(err), "ON EXCEPTION PERFORM calls ERR-PARA");
        assertTrue(cfg.successors(compute).contains(err), "ON SIZE ERROR PERFORM calls ERR-PARA");
        assertTrue(cfg.successors(err).contains(compute), "ERR-PARA returns after the CALL");
        assertTrue(cfg.successors(err).contains(after), "and after the COMPUTE");
    }

    /**
     * A COPY member holding a PERFORM, copied into two paragraphs, gives two statements and two
     * relations of one range; the model is built by hand so the range is shared exactly.
     */
    @Test
    void eachInclusionOfACopiedPerformHasItsOwnEdges() {
        SourceRange copied = range("ERRHNDL.cpy", 1);
        SimpleStatement first = new SimpleStatement("PERFORM", "PERFORM LOG-ERROR", copied);
        SimpleStatement second = new SimpleStatement("PERFORM", "PERFORM LOG-ERROR", copied);
        SimpleStatement afterFirst = new SimpleStatement("DISPLAY", "DISPLAY 'ONE'",
                range("PGM.cbl", 6));
        SimpleStatement stop = new SimpleStatement("STOP", "STOP RUN", range("PGM.cbl", 9));
        SimpleStatement log = new SimpleStatement("DISPLAY", "DISPLAY 'LOG'",
                range("PGM.cbl", 11));
        CobolSemanticModel model = new CobolSemanticModel("PGM", "PGM.cbl", List.of(),
                List.of(procedure("FIRST-PARA", List.of(first, afterFirst), 4),
                        procedure("SECOND-PARA", List.of(second, stop), 7),
                        procedure("LOG-ERROR", List.of(log), 10)),
                List.of(), List.of(
                        new PerformRelation("FIRST-PARA", "LOG-ERROR", Optional.empty(), copied),
                        new PerformRelation("SECOND-PARA", "LOG-ERROR", Optional.empty(), copied)),
                List.of(), List.of(), List.of());
        ControlFlowGraph cfg = CfgBuilder.build(model);
        CfgNode logNode = nodeOf(cfg, log);

        assertTrue(cfg.successors(nodeOf(cfg, first)).contains(logNode));
        assertTrue(cfg.successors(nodeOf(cfg, second)).contains(logNode));
        assertTrue(cfg.successors(logNode).contains(nodeOf(cfg, afterFirst)),
                "LOG-ERROR returns into FIRST-PARA");
        assertTrue(cfg.successors(logNode).contains(nodeOf(cfg, stop)),
                "and into SECOND-PARA");
    }

    private static final String SECTIONS = InlinePrograms.source(
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. SECTPGM.",
            "       PROCEDURE DIVISION.",
            "       MAIN-SECT SECTION.",
            "       MAIN-START.",
            "           PERFORM WORK-SECT",
            "           DISPLAY 'BACK'",
            "           STOP RUN.",
            "       WORK-SECT SECTION.",
            "       WORK-A.",
            "           DISPLAY 'A'.",
            "       WORK-B.",
            "           DISPLAY 'B'.",
            "       LAST-SECT SECTION.",
            "       LAST-A.",
            "           DISPLAY 'LAST'.");

    @Test
    void aPerformedSectionReturnsFromItsLastParagraph() {
        ControlFlowGraph cfg = InlinePrograms.cfg(SECTIONS);
        CfgNode back = InlinePrograms.nodeContaining(cfg, "'BACK'");

        assertTrue(cfg.successors(InlinePrograms.nodeContaining(cfg, "'B'")).contains(back));
        assertFalse(cfg.successors(InlinePrograms.nodeContaining(cfg, "'A'")).contains(back));
    }

    /**
     * A procedure name that contains UNTIL leaves the PERFORM a single call: the paragraph
     * returns to the statement after it.
     */
    @Test
    void aProcedureNameHoldingUntilGivesNoLoop() {
        ControlFlowGraph cfg = InlinePrograms.cfg(InlinePrograms.source(
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. UNTILPGM.",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           PERFORM WAIT-UNTIL-READY",
                "           STOP RUN.",
                "       WAIT-UNTIL-READY.",
                "           DISPLAY 'WAIT'."));
        CfgNode perform = InlinePrograms.nodeContaining(cfg, "PERFORM WAIT-UNTIL-READY");

        assertFalse(cfg.successors(InlinePrograms.nodeContaining(cfg, "'WAIT'"))
                .contains(perform), "the paragraph returns to the statement after the PERFORM");
        assertTrue(cfg.successors(InlinePrograms.nodeContaining(cfg, "'WAIT'"))
                .contains(InlinePrograms.nodeContaining(cfg, "STOP RUN")));
    }

    private static CfgNode nodeOf(ControlFlowGraph cfg, Statement statement) {
        return cfg.nodes().stream()
                .filter(node -> node.statement().isPresent()
                        && node.statement().orElseThrow() == statement)
                .findFirst().orElseThrow();
    }

    private static Procedure procedure(String name, List<Statement> statements, int line) {
        return new Procedure(name, ProcedureKind.PARAGRAPH, Optional.empty(), statements,
                range("PGM.cbl", line));
    }

    private static SourceRange range(String file, int line) {
        SourcePosition position = new SourcePosition(file, line, 12,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
        return new SourceRange(position, position);
    }
}
