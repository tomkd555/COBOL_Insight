package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.app.EngineWiring;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.ProcedureKind;
import jp.cobolinsight.core.semantic.ProgramLines;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.source.CopyInlineExpansion;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
import jp.cobolinsight.core.source.ExpandedCopyLine;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A statement a COPY written after other code brings in stands on that COPY statement's line. */
class ProgramLinesTest {

    @TempDir
    Path dir;

    @Test
    void aCopyAfterCodeOnItsLineGivesItsStatementsThatLine() throws Exception {
        Files.writeString(dir.resolve("FIRST.cpy"), "           DISPLAY 'FIRST'.\n");
        CobolSemanticModel model = parse("MIDCOPY", String.join("\n",
                "       IDENTIFICATION DIVISION.",                     // 1
                "       PROGRAM-ID.  MIDCOPY.",                        // 2
                "       PROCEDURE DIVISION.",                          // 3
                "       MAIN-PARA.",                                   // 4
                "           DISPLAY 'A' COPY FIRST.",                  // 5
                "       NEXT-PARA.",                                   // 6
                "           STOP RUN.",                                // 7
                ""));
        List<Statement> statements = model.procedures().get(0).statements();

        ProgramLines lines = new ProgramLines(model);

        assertNull(lines.copyLineOf(statements.get(0)));
        assertEquals(5, lines.copyLineOf(statements.get(1)));
        assertEquals(List.of(4, 5, 6, 7), List.of(lines.startOf(0), lines.endOf(0),
                lines.startOf(1), lines.endOf(1)));
    }

    /**
     * A program statement standing on a COPY's line after it leaves that inclusion's supply as
     * it is: the second COPY of one copybook supplies the second statement of its position.
     */
    @Test
    void aProgramStatementOnACopysLineLeavesTheInclusionsSupplyAsItIs() throws Exception {
        Files.writeString(dir.resolve("CB.cpy"), "           PERFORM P2.\n");
        CobolSemanticModel model = parse("TWOINC", String.join("\n",
                "       IDENTIFICATION DIVISION.",                     // 1
                "       PROGRAM-ID.  TWOINC.",                         // 2
                "       ENVIRONMENT DIVISION.",                        // 3
                "       DATA DIVISION.",                               // 4
                "       WORKING-STORAGE SECTION.",                     // 5
                "       01  WS-D PIC 9.",                              // 6
                "       PROCEDURE DIVISION.",                          // 7
                "       P1.",                                          // 8
                "           COPY CB. DISPLAY 'A'.",                    // 9
                "           COPY CB.",                                 // 10
                "           STOP RUN.",                                // 11
                "       P2.",                                          // 12
                "           DISPLAY 'P2'.",                            // 13
                ""));
        List<Statement> statements = model.procedures().get(0).statements();

        ProgramLines lines = new ProgramLines(model);

        assertEquals(java.util.Arrays.asList(9, null, 10, null), statements.stream()
                .map(lines::copyLineOf).toList());
    }

    /** A section whose last paragraph ends in copied code ends on that paragraph's COPY line. */
    @Test
    void aSectionInheritsTheCopiedEndOfItsLastParagraph() throws Exception {
        Files.writeString(dir.resolve("LASTCPY.cpy"), "           DISPLAY 'LAST'.\n");
        CobolSemanticModel model = parse("SECTEND", String.join("\n",
                "       IDENTIFICATION DIVISION.",                     // 1
                "       PROGRAM-ID.  SECTEND.",                        // 2
                "       PROCEDURE DIVISION.",                          // 3
                "       MAIN-SECT SECTION.",                           // 4
                "       MAIN-PARA.",                                   // 5
                "           DISPLAY 'A'",                              // 6
                "           COPY LASTCPY.",                            // 7
                "       NEXT-SECT SECTION.",                           // 8
                "       NEXT-PARA.",                                   // 9
                "           STOP RUN.",                                // 10
                ""));

        ProgramLines lines = new ProgramLines(model);

        assertEquals(List.of("MAIN-SECT", "MAIN-PARA"), model.procedures().subList(0, 2).stream()
                .map(Procedure::name).toList());
        assertEquals(List.of(4, 7, 5, 7), List.of(lines.startOf(0), lines.endOf(0),
                lines.startOf(1), lines.endOf(1)));
    }

    /**
     * A copied position no expansion holds, such as code the parser writes in itself, stays on
     * the last program line the walk reached.
     */
    @Test
    void aPositionNoExpansionHoldsStaysOnTheLastLineReached() {
        SimpleStatement own = statement("PGM.cbl", 5);
        SimpleStatement held = statement("ONE.cpy", 1);
        SimpleStatement unheld = statement("OTHER.cpy", 3);
        CobolSemanticModel model = model(List.of(own, held, unheld), List.of(
                new CopyInlineExpansion(6, "ONE", "ONE.cpy",
                        List.of(new ExpandedCopyLine(1, "", Optional.empty())))));

        ProgramLines lines = new ProgramLines(model);

        assertNull(lines.copyLineOf(own));
        assertEquals(6, lines.copyLineOf(held));
        assertEquals(6, lines.copyLineOf(unheld));
    }

    /** With no COPY, every statement is the program's own and every procedure keeps its lines. */
    @Test
    void aProgramWithoutCopyKeepsItsOwnLines() {
        SimpleStatement first = statement("PGM.cbl", 5);
        SimpleStatement second = statement("PGM.cbl", 6);
        CobolSemanticModel model = model(List.of(first, second), List.of());

        ProgramLines lines = new ProgramLines(model);

        assertNull(lines.copyLineOf(first));
        assertNull(lines.copyLineOf(second));
        assertEquals(List.of(4, 9), List.of(lines.startOf(0), lines.endOf(0)));
    }

    private CobolSemanticModel parse(String programId, String source) throws IOException {
        String path = dir.resolve(programId + ".cbl").toString();
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        return EngineWiring.cobolParser().parse(new DecodedSource(path, source, bytes,
                        new int[source.length()], new EncodingInfo("UTF-8", 1.0, false, false)),
                List.of(dir)).value().orElseThrow();
    }

    /** One paragraph of PGM.cbl, lines 4 to 9, holding the statements. */
    private static CobolSemanticModel model(List<Statement> statements,
            List<CopyInlineExpansion> expansions) {
        Procedure paragraph = new Procedure("MAIN-PARA", ProcedureKind.PARAGRAPH,
                Optional.empty(), statements, new SourceRange(position("PGM.cbl", 4),
                        position("PGM.cbl", 9)));
        return new CobolSemanticModel("PGM", "PGM.cbl", List.of(), List.of(paragraph), List.of(),
                List.of(), List.of(), List.of(), expansions);
    }

    private static SimpleStatement statement(String file, int line) {
        return new SimpleStatement("DISPLAY", "DISPLAY 'X'",
                new SourceRange(position(file, line), position(file, line)));
    }

    private static SourcePosition position(String file, int line) {
        return new SourcePosition(file, line, 12, SourcePosition.UNKNOWN_BYTE_OFFSET);
    }
}
