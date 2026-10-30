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
 * Verifies that a paragraph name defined in two sections resolves the way the stored paragraph
 * flow resolves it: inside a section to that section's paragraph, from a third section to the
 * first definition, and to the named section where the name is qualified.
 */
class CfgBuilderProcedureNamesTest {

    private static final String SOURCE = InlinePrograms.source(
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. NAMEPGM.",
            "       PROCEDURE DIVISION.",
            "       MAIN-SECT SECTION.",
            "       MAIN-START.",
            "           PERFORM P1 OF SECT-B",
            "           GO TO P1 IN SECT-B.",
            "       SECT-A SECTION.",
            "       A-START.",
            "           PERFORM P1",
            "           STOP RUN.",
            "       P1.",
            "           DISPLAY 'A-P1'.",
            "       SECT-B SECTION.",
            "       B-START.",
            "           PERFORM P1",
            "           STOP RUN.",
            "       P1.",
            "           DISPLAY 'B-P1'.");

    @Test
    void insideASectionANameMeansThatSectionsParagraph() {
        ControlFlowGraph cfg = InlinePrograms.cfg(SOURCE);
        CfgNode inA = InlinePrograms.nodeContaining(cfg, "'A-P1'");
        CfgNode inB = InlinePrograms.nodeContaining(cfg, "'B-P1'");
        List<CfgNode> performs = InlinePrograms.nodesContaining(cfg, "PERFORM P1");

        assertTrue(cfg.successors(performs.get(1)).contains(inA), "inside SECT-A: its own P1");
        assertFalse(cfg.successors(performs.get(1)).contains(inB));
        assertTrue(cfg.successors(performs.get(2)).contains(inB), "inside SECT-B: its own P1");
        assertFalse(cfg.successors(performs.get(2)).contains(inA));
    }

    @Test
    void aQualifiedNameResolvesToTheNamedSection() {
        ControlFlowGraph cfg = InlinePrograms.cfg(SOURCE);
        CfgNode inA = InlinePrograms.nodeContaining(cfg, "'A-P1'");
        CfgNode inB = InlinePrograms.nodeContaining(cfg, "'B-P1'");
        CfgNode perform = InlinePrograms.nodeContaining(cfg, "PERFORM P1 OF SECT-B");
        CfgNode goTo = cfg.nodes().stream()
                .filter(node -> node.statement().isPresent()
                        && node.statement().orElseThrow().range().start().line() == 7)
                .findFirst().orElseThrow();

        assertTrue(cfg.successors(perform).contains(inB));
        assertFalse(cfg.successors(perform).contains(inA));
        assertTrue(cfg.successors(goTo).contains(inB));
        assertFalse(cfg.successors(goTo).contains(inA));
    }

    /**
     * Che4z refuses an unqualified PERFORM of a name two other sections define, so this model is
     * built by hand.
     */
    @Test
    void fromAThirdSectionANameMeansItsFirstDefinition() {
        SimpleStatement perform = statement("PERFORM", "PERFORM P1", 3);
        SimpleStatement inA = statement("DISPLAY", "DISPLAY 'A-P1'", 6);
        SimpleStatement inB = statement("DISPLAY", "DISPLAY 'B-P1'", 9);
        CobolSemanticModel model = new CobolSemanticModel("NAMEPGM", "NAMEPGM.cbl", List.of(),
                List.of(procedure("MAIN-SECT", ProcedureKind.SECTION, null, List.of(perform), 2),
                        procedure("SECT-A", ProcedureKind.SECTION, null, List.of(), 4),
                        procedure("P1", ProcedureKind.PARAGRAPH, "SECT-A", List.of(inA), 5),
                        procedure("SECT-B", ProcedureKind.SECTION, null, List.of(), 7),
                        procedure("P1", ProcedureKind.PARAGRAPH, "SECT-B", List.of(inB), 8)),
                List.of(), List.of(new PerformRelation("MAIN-SECT", "P1", Optional.empty(),
                        perform.range())),
                List.of(), List.of(), List.of());
        ControlFlowGraph cfg = CfgBuilder.build(model);
        CfgNode performNode = InlinePrograms.nodeContaining(cfg, "PERFORM P1");

        assertTrue(cfg.successors(performNode).contains(InlinePrograms.nodeContaining(cfg, "'A-P1'")));
        assertFalse(cfg.successors(performNode).contains(InlinePrograms.nodeContaining(cfg, "'B-P1'")));
    }

    private static SimpleStatement statement(String verb, String text, int line) {
        return new SimpleStatement(verb, text, range(line));
    }

    private static Procedure procedure(String name, ProcedureKind kind, String section,
            List<Statement> statements, int line) {
        return new Procedure(name, kind, Optional.ofNullable(section), statements, range(line));
    }

    private static SourceRange range(int line) {
        SourcePosition position = new SourcePosition("NAMEPGM.cbl", line, 12,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
        return new SourceRange(position, position);
    }
}
