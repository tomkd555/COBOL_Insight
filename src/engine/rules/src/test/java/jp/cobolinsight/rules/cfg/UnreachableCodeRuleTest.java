package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic fixture verification for R011, a sibling of R007. Examines two judgments separately: an unreachable statement and a paragraph that is never called. */
class UnreachableCodeRuleTest {

    @TempDir
    Path tempDir;

    /** A statement placed after GOBACK is never reached by control flow. */
    private static final String AFTER_GOBACK = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID. FIX011.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "       01  WS-D PIC 9(01).",
            /*  6 */ "       PROCEDURE DIVISION.",
            /*  7 */ "       0000-MAIN.",
            /*  8 */ "           PERFORM 1000-STEP",
            /*  9 */ "           GOBACK.",
            /* 10 */ "       1000-STEP.",
            /* 11 */ "           GOBACK",
            /* 12 */ "           MOVE 1 TO WS-D.",
            "");

    /** A paragraph referenced by no PERFORM or GO TO, and not on the main fall-through path either. */
    private static final String UNUSED_PARAGRAPH = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID. FIX011B.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "       01  WS-D PIC 9(01).",
            /*  6 */ "       PROCEDURE DIVISION.",
            /*  7 */ "       0000-MAIN.",
            /*  8 */ "           MOVE 1 TO WS-D",
            /*  9 */ "           GOBACK.",
            /* 10 */ "       9000-DEAD.",
            /* 11 */ "           MOVE 2 TO WS-D.",
            "");

    /**
     * PERFORMs the same paragraph from inside a READ's NOT INVALID KEY clause. Unless a PERFORM
     * inside a conditional clause is also counted as a reference, a paragraph called only from
     * there would look unused.
     */
    private static final String PERFORMED_FROM_AN_IO_CLAUSE = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX011C.",
            "       ENVIRONMENT DIVISION.",
            "       INPUT-OUTPUT SECTION.",
            "       FILE-CONTROL.",
            "           SELECT MSTR ASSIGN TO MSTR",
            "                  ORGANIZATION IS INDEXED",
            "                  ACCESS MODE IS DYNAMIC",
            "                  RECORD KEY IS MST-KEY",
            "                  FILE STATUS IS WS-STATUS.",
            "       DATA DIVISION.",
            "       FILE SECTION.",
            "       FD  MSTR.",
            "       01  MST-REC.",
            "           05  MST-KEY  PIC X(08).",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-STATUS PIC X(02).",
            "       01  WS-D PIC 9(01).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           READ MSTR",
            "               INVALID KEY",
            "                   MOVE 1 TO WS-D",
            "               NOT INVALID KEY",
            "                   PERFORM 3210-UPDATE",
            "           END-READ",
            "           GOBACK.",
            "       3210-UPDATE.",
            "           MOVE 2 TO WS-D.",
            "");

    /** P1 is a paragraph of SECT-A and of SECT-B; only SECT-B's is performed, from inside SECT-B. */
    private static final String SAME_NAME_IN_TWO_SECTIONS = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID. FIX011E.",
            /*  3 */ "       PROCEDURE DIVISION.",
            /*  4 */ "       MAIN-SECT SECTION.",
            /*  5 */ "       MAIN-START.",
            /*  6 */ "           PERFORM SECT-B",
            /*  7 */ "           STOP RUN.",
            /*  8 */ "       SECT-A SECTION.",
            /*  9 */ "       P1.",
            /* 10 */ "           DISPLAY 'A'.",
            /* 11 */ "       SECT-B SECTION.",
            /* 12 */ "       B-START.",
            /* 13 */ "           PERFORM P1",
            /* 14 */ "           STOP RUN.",
            /* 15 */ "       P1.",
            /* 16 */ "           DISPLAY 'B'.",
            "");

    @Test
    void aNameTwoSectionsDefineReachesOnlyTheParagraphItResolvesTo() {
        assertEquals(List.of(9), run("FIX011E.cbl", SAME_NAME_IN_TWO_SECTIONS).stream()
                .map(f -> f.location().line()).toList(),
                "the PERFORM inside SECT-B reaches SECT-B's P1, and SECT-A's P1 stays unused");
    }

    /** A GO TO of another program names its own paragraph, whatever this program calls its own. */
    @Test
    void aGoToOfAnotherProgramReachesNothingHere() {
        String jumping = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. FIX011G.",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           GO TO 9000-DEAD.",
                "       9000-DEAD.",
                "           STOP RUN.",
                "");
        CobolSemanticModel unused = CfgFixtures.parse(tempDir, "FIX011B.cbl", UNUSED_PARAGRAPH);
        CobolSemanticModel other = CfgFixtures.parse(tempDir, "FIX011G.cbl", jumping);
        List<Finding> findings = new UnreachableCodeRule().evaluate(CfgFixtures.context(
                List.of(unused, other),
                Map.of(unused.sourceFile(), UNUSED_PARAGRAPH, other.sourceFile(), jumping)));

        assertEquals(List.of(unused.sourceFile() + ":10"), findings.stream()
                .map(f -> f.location().file() + ":" + f.location().line()).toList());
    }

    /**
     * Two files declare PROGRAM-ID FIX011J with different bodies. Each is analysed on its own
     * graph, so each reports the statement after its own GOBACK, in its own file.
     */
    @Test
    void twoFilesOfOneProgramIdEachHaveTheirOwnGraph() throws IOException {
        Path first = Files.createDirectories(tempDir.resolve("first"));
        Path second = Files.createDirectories(tempDir.resolve("second"));
        String one = AFTER_GOBACK.replace("FIX011.", "FIX011J.");
        String other = String.join("\n",
                /*  1 */ "       IDENTIFICATION DIVISION.",
                /*  2 */ "       PROGRAM-ID. FIX011J.",
                /*  3 */ "       DATA DIVISION.",
                /*  4 */ "       WORKING-STORAGE SECTION.",
                /*  5 */ "       01  WS-D PIC 9(01).",
                /*  6 */ "       PROCEDURE DIVISION.",
                /*  7 */ "       0000-MAIN.",
                /*  8 */ "           MOVE 1 TO WS-D",
                /*  9 */ "           MOVE 2 TO WS-D",
                /* 10 */ "           GOBACK",
                /* 11 */ "           MOVE 3 TO WS-D",
                /* 12 */ "           MOVE 4 TO WS-D.",
                "");
        CobolSemanticModel a = CfgFixtures.parse(first, "FIX011J.cbl", one);
        CobolSemanticModel b = CfgFixtures.parse(second, "FIX011J.cbl", other);

        List<Finding> findings = new UnreachableCodeRule().evaluate(CfgFixtures.context(
                List.of(a, b), Map.of(a.sourceFile(), one, b.sourceFile(), other)));

        assertEquals(List.of(a.sourceFile() + ":12", b.sourceFile() + ":11",
                        b.sourceFile() + ":12"),
                findings.stream().map(f -> f.location().file() + ":" + f.location().line())
                        .toList());
    }

    /**
     * The statement named as the one control leaves at is taken from the dead statement's own
     * file: the copybook's GOBACK at its line 4; the program's DISPLAY on its own line 4 stays
     * out of the message.
     */
    @Test
    void theLeavingStatementIsTakenFromTheDeadStatementsOwnFile() throws IOException {
        Path copybooks = Files.createDirectories(tempDir.resolve("cpy"));
        Files.writeString(copybooks.resolve("ENDS.cpy"), String.join("\n",
                "      * 1", "      * 2", "      * 3",
                "           GOBACK",
                "           DISPLAY 'DEAD'.", ""));
        String text = String.join("\n",
                /*  1 */ "       IDENTIFICATION DIVISION.",
                /*  2 */ "       PROGRAM-ID. FIX011L.",
                /*  3 */ "       PROCEDURE DIVISION.",
                /*  4 */ "       P1. DISPLAY 'A'",
                /*  5 */ "           COPY ENDS.",
                "");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX011L.cbl", text, copybooks);
        List<Finding> findings = new UnreachableCodeRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));

        assertEquals(1, findings.size(), findings::toString);
        assertEquals("ENDS.cpy:5", Path.of(findings.get(0).location().file()).getFileName()
                + ":" + findings.get(0).location().line());
        assertTrue(findings.get(0).message().contains("GOBACK 文（4行）"),
                findings.get(0).message());
    }

    private List<Finding> run(String fileName, String text) {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, fileName, text);
        return new UnreachableCodeRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsTheStatementAfterGoback() {
        List<Finding> findings = run("FIX011.cbl", AFTER_GOBACK);
        assertEquals(1, findings.size(), () -> "到達不能な文1件を検出すること: " + findings);
        assertEquals("R011", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertEquals(12, findings.get(0).location().line());
    }

    @Test
    void detectsTheParagraphNothingCalls() {
        Finding finding = unusedParagraphFinding(run("FIX011B.cbl", UNUSED_PARAGRAPH));
        assertEquals(10, finding.location().line());
        assertTrue(finding.message().contains("9000-DEAD"), finding.message());
    }

    /**
     * A PERFORM written in a NOT INVALID KEY phrase is a call edge of the CFG and a reference to
     * its paragraph, so the paragraph is used and its statement reachable.
     */
    @Test
    void countsAPerformInsideAnIoClauseAsAReference() {
        assertEquals(List.of(), run("FIX011C.cbl", PERFORMED_FROM_AN_IO_CLAUSE));
    }

    /**
     * Control that falls into a THRU range from the paragraph before it goes on past the range's
     * end; control that a GO TO inside the range takes to the range's end returns to the PERFORM.
     */
    @Test
    void theEndOfARangeStopsOnlyTheFlowInsideTheRange() {
        String fallsIn = String.join("\n",
                /*  1 */ "       IDENTIFICATION DIVISION.",
                /*  2 */ "       PROGRAM-ID. FIX011H.",
                /*  3 */ "       PROCEDURE DIVISION.",
                /*  4 */ "       MAIN-PARA.",
                /*  5 */ "           PERFORM A-PARA THRU A-EXIT.",
                /*  6 */ "       A-PARA.",
                /*  7 */ "           DISPLAY 'A'.",
                /*  8 */ "       A-EXIT.",
                /*  9 */ "           EXIT.",
                /* 10 */ "       NEXT-PARA.",
                /* 11 */ "           DISPLAY 'N'",
                /* 12 */ "           STOP RUN.",
                "");
        assertEquals(List.of(), run("FIX011H.cbl", fallsIn));

        String jumpsToTheEnd = String.join("\n",
                /*  1 */ "       IDENTIFICATION DIVISION.",
                /*  2 */ "       PROGRAM-ID. FIX011I.",
                /*  3 */ "       PROCEDURE DIVISION.",
                /*  4 */ "       MAIN-PARA.",
                /*  5 */ "           PERFORM A-PARA THRU A-EXIT",
                /*  6 */ "           STOP RUN.",
                /*  7 */ "       A-PARA.",
                /*  8 */ "           GO TO A-EXIT.",
                /*  9 */ "       A-EXIT.",
                /* 10 */ "           EXIT.",
                /* 11 */ "       NEXT-PARA.",
                /* 12 */ "           DISPLAY 'N'.",
                "");
        assertEquals(List.of(11), run("FIX011I.cbl", jumpsToTheEnd).stream()
                .map(f -> f.location().line()).toList());

        // Control falls into P1 from P0, so the GO TO of P1 carries it on to P2 and past it.
        String fallsInThenJumps = String.join("\n",
                /*  1 */ "       IDENTIFICATION DIVISION.",
                /*  2 */ "       PROGRAM-ID. FIX011K.",
                /*  3 */ "       PROCEDURE DIVISION.",
                /*  4 */ "       P0.",
                /*  5 */ "           PERFORM P1 THRU P2.",
                /*  6 */ "       P1.",
                /*  7 */ "           GO TO P2.",
                /*  8 */ "       P2.",
                /*  9 */ "           DISPLAY 'P2'.",
                /* 10 */ "       P3.",
                /* 11 */ "           DISPLAY 'P3'",
                /* 12 */ "           STOP RUN.",
                "");
        assertEquals(List.of(), run("FIX011K.cbl", fallsInThenJumps));
    }

    /**
     * Legacy structure: a READ whose AT END branches with GO TO, an ALTER that rewires a GO TO,
     * and paragraphs entered by falling in from a paragraph a GO TO reached.
     */
    private static final String LEGACY_FLOW = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID. FIX011D.",
            /*  3 */ "       ENVIRONMENT DIVISION.",
            /*  4 */ "       INPUT-OUTPUT SECTION.",
            /*  5 */ "       FILE-CONTROL.",
            /*  6 */ "           SELECT MSTR ASSIGN TO MSTR.",
            /*  7 */ "       DATA DIVISION.",
            /*  8 */ "       FILE SECTION.",
            /*  9 */ "       FD  MSTR.",
            /* 10 */ "       01  MST-REC  PIC X(08).",
            /* 11 */ "       WORKING-STORAGE SECTION.",
            /* 12 */ "       01  WS-D PIC 9(01).",
            /* 13 */ "       PROCEDURE DIVISION.",
            /* 14 */ "       0000-MAIN.",
            /* 15 */ "           OPEN INPUT MSTR",
            /* 16 */ "           ALTER 1100-SW TO PROCEED TO 1300-SECOND.",
            /* 17 */ "       1000-LOOP.",
            /* 18 */ "           READ MSTR AT END GO TO 2000-EOF.",
            /* 19 */ "           PERFORM 1100-SW.",
            /* 20 */ "           GO TO 1000-LOOP.",
            /* 21 */ "       1100-SW.",
            /* 22 */ "           GO TO 1200-FIRST.",
            /* 23 */ "       1200-FIRST.",
            /* 24 */ "           MOVE 1 TO WS-D.",
            /* 25 */ "       1300-SECOND.",
            /* 26 */ "           MOVE 2 TO WS-D.",
            /* 27 */ "       2000-EOF.",
            /* 28 */ "           CLOSE MSTR.",
            /* 29 */ "       2100-AFTER-EOF.",
            /* 30 */ "           MOVE 3 TO WS-D",
            /* 31 */ "           STOP RUN.",
            /* 32 */ "       9000-DEAD.",
            /* 33 */ "           MOVE 4 TO WS-D",
            /* 34 */ "           MOVE 5 TO WS-D.",
            "");

    @Test
    void followsNestedGoToAlterAndFallThrough() {
        List<Finding> findings = run("FIX011D.cbl", LEGACY_FLOW);
        assertEquals(List.of(32), findings.stream().map(f -> f.location().line()).toList(),
                () -> "2000-EOF (READ AT END GO TO), 1300-SECOND (ALTER) and 2100-AFTER-EOF "
                        + "(fall-through from a GO TO target) are reached; only 9000-DEAD is "
                        + "reported, once, without one row per statement: " + findings);
    }

    /** A PERFORM THRU returns at the end of its range; what follows the range is not entered through it. */
    private static final String THRU_RANGE = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID. FIX011E.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "       01  WS-D PIC 9(01).",
            /*  6 */ "       PROCEDURE DIVISION.",
            /*  7 */ "       0000-MAIN.",
            /*  8 */ "           PERFORM 1000-A THRU 1000-EXIT",
            /*  9 */ "           STOP RUN.",
            /* 10 */ "       1000-A.",
            /* 11 */ "           MOVE 1 TO WS-D.",
            /* 12 */ "       1000-EXIT.",
            /* 13 */ "           EXIT.",
            /* 14 */ "       9000-DEAD.",
            /* 15 */ "           MOVE 2 TO WS-D.",
            "");

    @Test
    void stopsAtTheEndOfAPerformThruRange() {
        List<Finding> findings = run("FIX011E.cbl", THRU_RANGE);
        assertEquals(List.of(14), findings.stream().map(f -> f.location().line()).toList(),
                () -> "1000-EXIT returns to the PERFORM, so 9000-DEAD is unused: " + findings);
    }

    /** CICS branches to a HANDLE label the way a GO TO would. */
    private static final String HANDLE_LABEL = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID. FIX011F.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "       01  WS-D PIC 9(01).",
            /*  6 */ "       PROCEDURE DIVISION.",
            /*  7 */ "       0000-MAIN.",
            /*  8 */ "           EXEC CICS HANDLE CONDITION MAPFAIL(8100-MAPFAIL) END-EXEC",
            /*  9 */ "           GOBACK.",
            /* 10 */ "       8100-MAPFAIL.",
            /* 11 */ "           MOVE 1 TO WS-D.",
            "");

    @Test
    void countsAHandleConditionLabelAsAReference() {
        assertEquals(List.of(), run("FIX011F.cbl", HANDLE_LABEL),
                "the label of a HANDLE CONDITION is reached and its statement reachable");
    }

    private static Finding unusedParagraphFinding(List<Finding> findings) {
        return findings.stream().filter(f -> f.message().contains("を呼ぶ PERFORM・GO TO がありません"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("呼ばれない段落の検出が無い: " + findings));
    }
}
