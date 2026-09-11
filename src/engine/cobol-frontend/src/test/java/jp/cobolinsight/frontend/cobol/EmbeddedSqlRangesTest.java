package jp.cobolinsight.frontend.cobol;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.spi.ParseOutcome;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The column-aware EXEC SQL scan, the masking built on it, and the retry it exists for.
 *
 * <p>The fixture has the shape of the Db2 lab programs of the open COBOL course: a
 * {@code DECLARE Z#####T TABLE} in WORKING-STORAGE whose {@code #} Db2 allows and Che4z's own SQL
 * grammar does not. Che4z therefore fails the whole program on the first pass, and the program
 * comes back only because the retry masks that block.</p>
 */
class EmbeddedSqlRangesTest {

    private static final String FIXTURE = read("/sql/DECLTBL.cbl");

    private static String read(String resource) {
        try (InputStream in = EmbeddedSqlRangesTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is not on the test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void everyBlockIsFoundAndClassifiedByItsFirstKeyword() {
        List<EmbeddedSqlRanges.Block> blocks = EmbeddedSqlRanges.scan(FIXTURE);

        assertEquals(List.of(EmbeddedSqlRanges.Kind.INCLUDE,
                EmbeddedSqlRanges.Kind.BEGIN_DECLARE_SECTION,
                EmbeddedSqlRanges.Kind.END_DECLARE_SECTION,
                EmbeddedSqlRanges.Kind.OTHER,
                EmbeddedSqlRanges.Kind.OTHER,
                EmbeddedSqlRanges.Kind.WHENEVER,
                EmbeddedSqlRanges.Kind.OTHER,
                EmbeddedSqlRanges.Kind.OTHER,
                EmbeddedSqlRanges.Kind.OTHER),
                blocks.stream().map(EmbeddedSqlRanges.Block::kind).toList());
    }

    @Test
    void aBlockSpanningLinesKeepsItsOwnStartAndEndPosition() {
        EmbeddedSqlRanges.Block declareTable = EmbeddedSqlRanges.scan(FIXTURE).get(3);

        assertEquals(13, declareTable.startLine());
        assertEquals(12, declareTable.startColumn());
        assertEquals(16, declareTable.endLine());
        assertEquals(28, declareTable.endColumn());
        assertTrue(declareTable.contains(14, 30));
        assertFalse(declareTable.contains(12, 30));
        assertFalse(declareTable.inProcedureDivision());
        assertTrue(EmbeddedSqlRanges.scan(FIXTURE).get(6).inProcedureDivision());
    }

    @Test
    void aCommentedOutBlockIsNotFound() {
        String text = """
                       IDENTIFICATION DIVISION.
                      *    EXEC SQL COMMIT END-EXEC.
                           EXEC SQL COMMIT END-EXEC.
                """;

        assertEquals(1, EmbeddedSqlRanges.scan(text).size());
    }

    @Test
    void execAndSqlOnSeparateLinesAreOneBlock() {
        String text = """
                           EXEC
                               SQL COMMIT
                           END-EXEC.
                """;

        List<EmbeddedSqlRanges.Block> blocks = EmbeddedSqlRanges.scan(text);
        assertEquals(1, blocks.size());
        assertEquals(1, blocks.get(0).startLine());
        assertEquals(3, blocks.get(0).endLine());
    }

    @Test
    void textPastColumn72IsNotPartOfABlock() {
        String line = "           EXEC SQL COMMIT END-EXEC.";
        String padded = line + " ".repeat(72 - line.length()) + "EXEC SQL COMMIT END-EXEC.\n";

        assertEquals(1, EmbeddedSqlRanges.scan(padded).size());
    }

    @Test
    void maskingRewritesOnlyTheBodiesOfExecutableBlocks() {
        String masked = EmbeddedSqlRanges.mask(FIXTURE, EmbeddedSqlRanges.scan(FIXTURE));

        assertTrue(masked.contains("INCLUDE SQLCA"), "INCLUDE is never masked");
        assertTrue(masked.contains("BEGIN DECLARE SECTION"), "declare sections are never masked");
        assertTrue(masked.contains("END DECLARE SECTION"), "declare sections are never masked");
        assertTrue(masked.contains("WHENEVER SQLERROR CONTINUE"), "WHENEVER is never masked");
        assertFalse(masked.contains("Z#####T"), "the table declaration is masked");
        assertFalse(masked.contains("FETCH CUR1"), "the procedure block is masked");
        assertTrue(masked.contains(EmbeddedSqlRanges.DATA_PLACEHOLDER));
        assertTrue(masked.contains(EmbeddedSqlRanges.PROCEDURE_PLACEHOLDER));
        assertTrue(masked.contains("SQL DECLARATION FOR VIEW ACCOUNTS"), "comments are untouched");
    }

    @Test
    void maskingKeepsEveryLineBreakAndEveryColumnOutsideTheCodeArea() {
        String masked = EmbeddedSqlRanges.mask(FIXTURE, EmbeddedSqlRanges.scan(FIXTURE));

        assertEquals(FIXTURE.length(), masked.length());
        String[] before = FIXTURE.split("\n", -1);
        String[] after = masked.split("\n", -1);
        assertEquals(before.length, after.length);
        for (int i = 0; i < before.length; i++) {
            assertEquals(before[i].length(), after[i].length(), "line " + (i + 1) + " length");
            int head = Math.min(7, before[i].length());
            assertEquals(before[i].substring(0, head), after[i].substring(0, head),
                    "line " + (i + 1) + " columns 1-7");
            if (before[i].length() > 72) {
                assertEquals(before[i].substring(72), after[i].substring(72),
                        "line " + (i + 1) + " columns 73 and past");
            }
        }
        assertTrue(masked.contains("SEQ00140"), "the identification area is kept");
    }

    @Test
    void aCommentLineInsideABlockIsNotMasked() {
        String text = """
                       DATA DIVISION.
                           EXEC SQL DECLARE Z#####T TABLE
                      *      THE VIEW OF THE ACCOUNTS FILE
                                   (ACCTNO CHAR(8) NOT NULL)
                           END-EXEC.
                """;

        String masked = EmbeddedSqlRanges.mask(text, EmbeddedSqlRanges.scan(text));

        assertTrue(masked.contains("THE VIEW OF THE ACCOUNTS FILE"), "the comment is untouched");
        assertFalse(masked.contains("Z#####T"), "the body is masked");
    }

    @Test
    void aBlockOnALineFlaggedWithASlashIsNotFound() {
        String text = """
                       IDENTIFICATION DIVISION.
                      /    EXEC SQL COMMIT END-EXEC.
                           EXEC SQL COMMIT END-EXEC.
                """;

        assertEquals(1, EmbeddedSqlRanges.scan(text).size());
    }

    @Test
    void anEndExecWithoutAPeriodClosesItsBlock() {
        String text = """
                       PROCEDURE DIVISION.
                           EXEC SQL COMMIT END-EXEC
                           EXEC SQL ROLLBACK END-EXEC.
                """;

        List<EmbeddedSqlRanges.Block> blocks = EmbeddedSqlRanges.scan(text);
        assertEquals(2, blocks.size());
        assertEquals(2, blocks.get(0).endLine());
        assertEquals(3, blocks.get(1).endLine());
    }

    @Test
    void carriageReturnsDoNotShiftTheColumnsAScanReports() {
        String text = "       IDENTIFICATION DIVISION.\r\n"
                + "           EXEC SQL COMMIT END-EXEC.\r\n";

        List<EmbeddedSqlRanges.Block> blocks = EmbeddedSqlRanges.scan(text);
        assertEquals(1, blocks.size());
        assertEquals(2, blocks.get(0).startLine());
        assertEquals(12, blocks.get(0).startColumn());
        assertEquals(2, blocks.get(0).endLine());
        assertEquals(35, blocks.get(0).endColumn());
    }

    @Test
    void aBlockWithNoEndExecBeforeTheNextOneIsDropped() {
        // The END-EXEC of the first block is broken over a continuation line, so the scan does
        // not see it. The block must be dropped, not run on to the END-EXEC of the second: mask()
        // would blank the MOVE that stands between them.
        String text = """
                       PROCEDURE DIVISION.
                           EXEC SQL COMMIT END-
                      -         EXEC.
                           MOVE 1 TO WS-A.
                           EXEC SQL ROLLBACK END-EXEC.
                """;

        List<EmbeddedSqlRanges.Block> blocks = EmbeddedSqlRanges.scan(text);

        assertEquals(1, blocks.size(), () -> "only the closed block is kept: " + blocks);
        assertEquals(5, blocks.get(0).startLine());
        String masked = EmbeddedSqlRanges.mask(text, blocks);
        assertTrue(masked.contains("MOVE 1 TO WS-A."), "the COBOL between the blocks is kept");
    }

    @Test
    void aNarrowBlockOfTheDataDivisionStillFitsAPlaceholder() {
        String text = """
                       DATA DIVISION.
                           EXEC SQL DECLARE C1 CURSOR FOR S1 END-EXEC.
                """;

        String masked = EmbeddedSqlRanges.mask(text, EmbeddedSqlRanges.scan(text));

        assertNotNull(masked, () -> "a one-line block is masked too: " + text);
        assertTrue(masked.contains(EmbeddedSqlRanges.DATA_PLACEHOLDER), masked);
        assertFalse(masked.contains("CURSOR"), masked);
    }

    @Test
    void theDivisionOfABlockIsReadFromTheHeaderBeforeIt() {
        String text = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. FIRST.
                       PROCEDURE DIVISION.
                           EXEC SQL COMMIT END-EXEC.
                       END PROGRAM FIRST.
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. SECOND.
                       DATA DIVISION.
                           EXEC SQL DECLARE T1 TABLE (A CHAR(1)) END-EXEC.
                       PROCEDURE DIVISION.
                           GOBACK.
                """;

        List<EmbeddedSqlRanges.Block> blocks = EmbeddedSqlRanges.scan(text);

        assertEquals(2, blocks.size());
        assertTrue(blocks.get(0).inProcedureDivision(), "the first block is in a PROCEDURE");
        assertFalse(blocks.get(1).inProcedureDivision(),
                "the second program's DATA DIVISION takes over from the first PROCEDURE");
    }

    @Test
    void theWordsOfADivisionHeaderInsideAStatementDoNotMoveTheDivision() {
        String text = """
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                       PROCEDURE DIVISION.
                       MAIN-PARA.
                           DISPLAY 'PROCEDURE DIVISION'.
                           EXEC SQL COMMIT END-EXEC.
                """;

        List<EmbeddedSqlRanges.Block> blocks = EmbeddedSqlRanges.scan(text);
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).inProcedureDivision());

        String inData = """
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                       01  WS-A PIC X(20) VALUE 'PROCEDURE DIVISION'.
                           EXEC SQL DECLARE Z#####T TABLE (A CHAR(1)) END-EXEC.
                """;

        List<EmbeddedSqlRanges.Block> dataBlocks = EmbeddedSqlRanges.scan(inData);
        assertEquals(1, dataBlocks.size());
        assertFalse(dataBlocks.get(0).inProcedureDivision(),
                "a VALUE clause naming a division is not a division header");
        assertTrue(EmbeddedSqlRanges.mask(inData, dataBlocks)
                .contains(EmbeddedSqlRanges.DATA_PLACEHOLDER));
    }

    @Test
    void anErrorInABlockTheMaskNeverRewritesLeavesTheProgramFailing() {
        // A WHENEVER block is never masked, so a retry could not change the outcome: the program
        // must fail with what Che4z said, not come back through a mask that touched nothing.
        String text = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. WHENBAD.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                       01  WS-A PIC X(8).
                       PROCEDURE DIVISION.
                       MAIN-PARA.
                           EXEC SQL WHENEVER SQLERROR FOO BAR END-EXEC.
                           GOBACK.
                """;

        Finding failure = parse("WHENBAD.cbl", text).failureFinding()
                .orElseThrow(() -> new AssertionError("the broken WHENEVER was swallowed"));

        assertEquals("Syntax error on 'FOO'", failure.message());
    }

    @Test
    void aProgramChe4zRejectsForItsSqlComesBackThroughTheRetry() {
        ParseOutcome<CobolSemanticModel> outcome = parse("DECLTBL.cbl", FIXTURE);

        CobolSemanticModel model = outcome.value().orElseThrow(() -> new AssertionError(
                "DECLTBL.cbl did not parse: " + outcome.failureFinding().orElse(null)));
        assertEquals("DECLTBL", model.programId());
        List<String> sql = model.embeddedBlocks().stream()
                .filter(block -> block.kind() == EmbeddedBlockKind.SQL)
                .map(EmbeddedBlock::text).toList();
        assertTrue(sql.stream().anyMatch(text -> text.contains("Z#####T")),
                () -> "the blocks keep the original SQL: " + sql);
        assertTrue(sql.stream().anyMatch(text -> text.contains("FETCH CUR1")),
                () -> "the blocks keep the original SQL: " + sql);
    }

    @Test
    void anUndefinedHostVariableInsideABlockStillFailsWithItsOwnMessage() {
        // Masking would delete the reference and report the program as parsed. A semantic error
        // is not a grammar rejection, so the retry must not fire for it.
        String text = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. UNDEFHV.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                           EXEC SQL INCLUDE SQLCA END-EXEC.
                       01  WS-ACCTNO          PIC X(8).
                       PROCEDURE DIVISION.
                       MAIN-PARA.
                           EXEC SQL
                               SELECT ACCTNO INTO :WS-MISSING FROM ACCT
                           END-EXEC.
                           GOBACK.
                """;

        Finding failure = parse("UNDEFHV.cbl", text).failureFinding()
                .orElseThrow(() -> new AssertionError("the undefined host variable was swallowed"));

        assertEquals("Variable WS-MISSING is not defined", failure.message());
    }

    @Test
    void aProgramThatFailsOutsideAnyBlockKeepsItsOriginalMessage() {
        String text = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. OUTSIDE.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                       01  WS-A PIC X(8).
                       PROCEDURE DIVISION.
                       MAIN-PARA.
                           MOVE 1 TO
                           GOBACK.
                """;

        Finding failure = parse("OUTSIDE.cbl", text).failureFinding()
                .orElseThrow(() -> new AssertionError("the broken statement was swallowed"));

        assertTrue(failure.message().contains("GOBACK"), failure.message());
    }

    @Test
    void aProgramTheMaskedRunAlsoRejectsFailsWithTheMessageOfTheFirstRun() {
        // The SQL is rejected by Che4z's grammar and the retry masks it, but the program is
        // broken outside the block as well: the message the user sees is the one about the SQL,
        // never one about the placeholder text the mask wrote.
        String text = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. BOTHBAD.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                           EXEC SQL DECLARE Z#####T TABLE
                                   (ACCTNO     CHAR(8)  NOT NULL)
                                   END-EXEC.
                       PROCEDURE DIVISION.
                       MAIN-PARA.
                           MOVE 1 TO
                           GOBACK.
                """;

        Finding failure = parse("BOTHBAD.cbl", text).failureFinding()
                .orElseThrow(() -> new AssertionError("the broken program was swallowed"));

        assertTrue(failure.message().startsWith("No viable alternative at input"),
                failure.message());
        assertFalse(failure.message().contains(EmbeddedSqlRanges.DATA_PLACEHOLDER),
                failure.message());
    }

    private static ParseOutcome<CobolSemanticModel> parse(String fileName, String text) {
        return new Che4zCobolParser().parse(
                TestSources.fromText(TestSources.REPO_ROOT.resolve(fileName).toString(), text),
                List.of());
    }
}
