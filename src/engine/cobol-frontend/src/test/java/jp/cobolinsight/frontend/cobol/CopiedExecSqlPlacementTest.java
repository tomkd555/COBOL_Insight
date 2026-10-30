package jp.cobolinsight.frontend.cobol;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.ProcedureMap;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.Statements;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An EXEC SQL or EXEC CICS block a COPY member brings in. Che4z's dialects add such a block to
 * the tree after the parse, at its copybook position, in one of these places: the last child of a
 * sentence, under the CopyNode, a direct child of the paragraph, or nested inside an earlier block
 * from the same copybook line. The blocks of every inclusion of one copybook gather at one place,
 * which may lie in another paragraph or branch. The statement list puts each block back where its
 * COPY statement stands, once.
 */
class CopiedExecSqlPlacementTest {

    private static final Map<String, String> COPYBOOKS = Map.of(
            "SQLERR", "           EXEC SQL WHENEVER SQLERROR GO TO 9900-ERR END-EXEC\n",
            "SQLERP", "           EXEC SQL WHENEVER SQLERROR GO TO 9900-ERR END-EXEC.\n",
            "CPYS", "           EXEC SQL WHENEVER SQLERROR GO TO 9900-ERR END-EXEC\n",
            "CPYW", "           EXEC SQL WHENEVER SQLERROR CONTINUE END-EXEC\n",
            "CPYD", "           EXEC SQL UPDATE T1 SET C = 1 END-EXEC\n",
            "CPYC", "           EXEC CICS LINK PROGRAM('PGMC') END-EXEC\n");

    @TempDir
    Path dir;

    @Test
    void aBlockCopiedInTheMiddleOfASentenceStandsAtItsCopy() throws IOException {
        CobolSemanticModel model = parse(
                "           MOVE 1 TO WS-D\n           COPY SQLERR.\n           DISPLAY 'B'\n");

        assertEquals(List.of("MOVE REPRO.cbl:9", "EXEC SQL SQLERR.cpy:1", "DISPLAY REPRO.cbl:11",
                "GOBACK REPRO.cbl:12"), verbs(model, 0));
        assertEquals(1, model.embeddedBlocks().size());
        assertEquals(model.embeddedBlocks(), blocksOfStatements(model));
    }

    @Test
    void aBlockCopiedAtTheStartOfASentenceStandsAtItsCopy() throws IOException {
        CobolSemanticModel model = parse("           COPY SQLERR.\n           DISPLAY 'B'\n");

        assertEquals(List.of("EXEC SQL SQLERR.cpy:1", "DISPLAY REPRO.cbl:10",
                "GOBACK REPRO.cbl:11"), verbs(model, 0));
        assertEquals(1, model.embeddedBlocks().size());
    }

    /** Two blocks of one copybook stand at its COPY in the copybook's own order. */
    @Test
    void twoBlocksOfOneCopybookKeepTheirOrder() throws IOException {
        Files.writeString(dir.resolve("TWOSQL.cpy"), String.join("\n",
                "           EXEC SQL WHENEVER SQLERROR CONTINUE END-EXEC",
                "           EXEC SQL WHENEVER SQLERROR GO TO 9900-ERR END-EXEC", ""));
        CobolSemanticModel model = parse(
                "           MOVE 1 TO WS-D\n           COPY TWOSQL.\n           DISPLAY 'B'\n");

        assertEquals(List.of("MOVE REPRO.cbl:9", "EXEC SQL TWOSQL.cpy:1", "EXEC SQL TWOSQL.cpy:2",
                "DISPLAY REPRO.cbl:11", "GOBACK REPRO.cbl:12"), verbs(model, 0));
    }

    /** Che4z hangs the program's own block written after a COPY off the paragraph directly. */
    @Test
    void aBlockTheParagraphHoldsDirectlyIsMapped() throws IOException {
        CobolSemanticModel model = parse("           COPY CPYS.\n"
                + "           EXEC SQL WHENEVER SQLERROR CONTINUE END-EXEC.\n");

        assertEquals(List.of("EXEC SQL CPYS.cpy:1", "EXEC SQL REPRO.cbl:10", "GOBACK REPRO.cbl:11"),
                verbs(model, 0));
        assertEquals(model.embeddedBlocks(), blocksOfStatements(model));
    }

    @Test
    void anExecCicsTheParagraphHoldsDirectlyIsMapped() throws IOException {
        CobolSemanticModel model = parse("           COPY CPYD.\n"
                + "           EXEC CICS LINK PROGRAM('PGMC') END-EXEC.\n");

        assertEquals(List.of("EXEC SQL CPYD.cpy:1", "EXEC CICS REPRO.cbl:10", "GOBACK REPRO.cbl:11"),
                verbs(model, 0));
    }

    /** Che4z hangs the second block under its CopyNode after the GOBACK sentence. */
    @Test
    void consecutiveCopiesEachBringTheirBlock() throws IOException {
        CobolSemanticModel model = parse(
                "           COPY CPYS.\n           COPY CPYD.\n           DISPLAY 'X'.\n");

        assertEquals(List.of("EXEC SQL CPYS.cpy:1", "EXEC SQL CPYD.cpy:1", "DISPLAY REPRO.cbl:11",
                "GOBACK REPRO.cbl:12"), verbs(model, 0));
        assertEquals(model.embeddedBlocks(), blocksOfStatements(model));
    }

    @Test
    void anExecCicsWrittenBetweenTwoCopiesStandsBetweenTheirBlocks() throws IOException {
        CobolSemanticModel model = parse("           COPY CPYS.\n"
                + "           EXEC CICS SYNCPOINT END-EXEC\n           COPY CPYD.\n");

        assertEquals(List.of("EXEC SQL CPYS.cpy:1", "EXEC CICS REPRO.cbl:10", "EXEC SQL CPYD.cpy:1",
                "GOBACK REPRO.cbl:12"), verbs(model, 0));
    }

    /** Che4z appends a copied EXEC CICS under its CopyNode at the sentence's end. */
    @Test
    void aCopiedExecCicsStandsAtItsCopy() throws IOException {
        CobolSemanticModel model = parse("           MOVE 1 TO WS-D\n           COPY CPYC.\n"
                + "           DISPLAY 'B'\n           STOP RUN.\n");

        assertEquals(List.of("MOVE REPRO.cbl:9", "EXEC CICS CPYC.cpy:1", "DISPLAY REPRO.cbl:11",
                "STOP REPRO.cbl:12", "GOBACK REPRO.cbl:13"), verbs(model, 0));
    }

    /**
     * One copybook included twice, its block ending in a period: Che4z nests the first
     * inclusion's block inside the second's. Each stands at its own COPY.
     */
    @Test
    void aBlockNestedInsideTheNextInclusionsBlockStandsAtItsOwnCopy() throws IOException {
        CobolSemanticModel model = parse(
                "           COPY SQLERP.\n           EXEC SQL INSERT INTO T VALUES (1) END-EXEC.\n"
                        + "           EXEC SQL WHENEVER SQLERROR CONTINUE END-EXEC.\n"
                        + "           COPY SQLERP.\n"
                        + "           EXEC SQL INSERT INTO T VALUES (2) END-EXEC.\n");

        assertEquals(List.of("EXEC SQL SQLERP.cpy:1", "EXEC SQL REPRO.cbl:10",
                "EXEC SQL REPRO.cbl:11", "EXEC SQL SQLERP.cpy:1", "EXEC SQL REPRO.cbl:13",
                "GOBACK REPRO.cbl:14"), verbs(model, 0));
        assertEquals(model.embeddedBlocks(), blocksOfStatements(model));
    }

    /** Both inclusions' blocks gather in the first paragraph; each goes back to its own. */
    @Test
    void aCopybookIncludedInTwoParagraphsGivesEachItsBlock() throws IOException {
        CobolSemanticModel model = parse("           COPY CPYS.\n           DISPLAY 'A'.\n"
                + "       0001-P.\n           COPY CPYS.\n           DISPLAY 'B'.\n");

        assertEquals(List.of("EXEC SQL CPYS.cpy:1", "DISPLAY REPRO.cbl:10"), verbs(model, 0));
        assertEquals(List.of("EXEC SQL CPYS.cpy:1", "DISPLAY REPRO.cbl:13", "GOBACK REPRO.cbl:14"),
                verbs(model, 1));
    }

    /** Che4z lists the two CopyNodes inside an IF last-written first. */
    @Test
    void twoCopiesInsideAnIfKeepTheirWrittenOrder() throws IOException {
        CobolSemanticModel model = parse("           IF WS-D = 1\n           COPY CPYS.\n"
                + "           COPY CPYW.\n           DISPLAY 'Y'\n           END-IF.\n");

        assertEquals(List.of("EXEC SQL CPYS.cpy:1", "EXEC SQL CPYW.cpy:1", "DISPLAY REPRO.cbl:12",
                "GOBACK REPRO.cbl:14"), verbs(model, 0));
    }

    /** Every inclusion of CPYW gathers under the PERFORM's CopyNode; each goes back to its own. */
    @Test
    void copiesInAnInlinePerformAndAnEvaluateEachKeepTheirBlocks() throws IOException {
        CobolSemanticModel model = parse("           PERFORM UNTIL WS-D = 1\n"
                + "           COPY CPYS.\n           COPY CPYW.\n"
                + "           DISPLAY 'Y'\n           END-PERFORM\n"
                + "           EVALUATE WS-D WHEN 1\n           COPY CPYS.\n           COPY CPYW.\n"
                + "           DISPLAY 'Y'\n           WHEN OTHER\n           COPY CPYC.\n"
                + "           COPY CPYW.\n           DISPLAY 'Z'\n           END-EVALUATE.\n");

        assertEquals(List.of("EXEC SQL CPYS.cpy:1", "EXEC SQL CPYW.cpy:1", "DISPLAY REPRO.cbl:12",
                "EXEC SQL CPYS.cpy:1", "EXEC SQL CPYW.cpy:1", "DISPLAY REPRO.cbl:17",
                "EXEC CICS CPYC.cpy:1", "EXEC SQL CPYW.cpy:1", "DISPLAY REPRO.cbl:21",
                "GOBACK REPRO.cbl:23"), verbs(model, 0));
        assertEquals(model.embeddedBlocks(), blocksOfStatements(model));
    }

    private CobolSemanticModel parse(String body) throws IOException {
        for (Map.Entry<String, String> copybook : COPYBOOKS.entrySet()) {
            Files.writeString(dir.resolve(copybook.getKey() + ".cpy"), copybook.getValue());
        }
        String text = "       IDENTIFICATION DIVISION.\n"
                + "       PROGRAM-ID. REPRO.\n"
                + "       DATA DIVISION.\n"
                + "       WORKING-STORAGE SECTION.\n"
                + "           EXEC SQL INCLUDE SQLCA END-EXEC.\n"
                + "       01 WS-D PIC 9.\n"
                + "       PROCEDURE DIVISION.\n"
                + "       0000-MAIN.\n"
                + body
                + "           GOBACK.\n"
                + "       9900-ERR.\n"
                + "           GOBACK.\n";
        String path = dir.resolve("REPRO.cbl").toString();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new Che4zCobolParser().parse(new DecodedSource(path, text, bytes,
                        new int[text.length()], new EncodingInfo("UTF-8", 1.0, false, false)),
                List.of(dir)).value().orElseThrow();
    }

    /** The simple statements of one procedure, branches included, in walk order. */
    private static List<String> verbs(CobolSemanticModel model, int procedure) {
        List<String> verbs = new ArrayList<>();
        Statements.walk(model.procedures().get(procedure).statements(), s -> {
            if (s instanceof SimpleStatement simple) {
                verbs.add(simple.verb() + " " + Path.of(s.range().start().file()).getFileName()
                        + ":" + s.range().start().line());
            }
        });
        return verbs;
    }

    private static List<EmbeddedBlock> blocksOfStatements(CobolSemanticModel model) {
        ProcedureMap map = new ProcedureMap(model);
        List<EmbeddedBlock> blocks = new ArrayList<>();
        for (var procedure : model.procedures()) {
            Statements.walk(procedure.statements(), s -> blocks.addAll(map.blocksOf(s)));
        }
        return blocks;
    }
}
