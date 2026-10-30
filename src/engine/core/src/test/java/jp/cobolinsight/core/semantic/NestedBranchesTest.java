package jp.cobolinsight.core.semantic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The branches a statement's text carries: nested GO TOs, ALTER pairs and HANDLE labels. */
class NestedBranchesTest {

    private static List<String> targets(List<NestedBranches.Branch> branches) {
        return branches.stream().map(NestedBranches.Branch::target).toList();
    }

    @Test
    void readsEveryPairOfAnAlter() {
        assertEquals(List.of("3300-SECOND", "3400-THIRD"), targets(NestedBranches.alterBranches(
                "ALTER 3100-SW TO PROCEED TO 3300-SECOND, 3200-SW TO PROCEED TO 3400-THIRD")));
        assertEquals(List.of("3300-SECOND"),
                targets(NestedBranches.alterBranches("ALTER 3100-SW TO 3300-SECOND")));
        assertEquals(List.of(),
                targets(NestedBranches.alterBranches("MOVE 'ALTER X TO Y' TO WS-A")));
    }

    /** The span of a multi-line statement can hold a comment line; column 7 of a later line says so. */
    @Test
    void ignoresACommentedOutBranchInsideAStatement() {
        String text = String.join("\n",
                "READ FLT-FILE",
                "      *    AT END GO TO 9900-OLD-EOF",
                "           AT END GO TO 9000-EOF");
        assertEquals(List.of("9000-EOF"), targets(NestedBranches.goToBranches(text)));
    }

    /** Line and offset count within the statement text, comment lines and literals included. */
    @Test
    void placesEachBranchAndReadsItsSection() {
        String text = String.join("\n",
                "READ F1 INVALID KEY DISPLAY 'GO TO X' GO TO P1 OF SECT-B",
                "      *    AT END GO TO OLD",
                "           NOT INVALID KEY GO TO P2");
        assertEquals(List.of(
                        new NestedBranches.Branch("P1", Optional.of("SECT-B"), 0, 38),
                        new NestedBranches.Branch("P2", Optional.empty(), 2, 27)),
                NestedBranches.goToBranches(text));
        assertEquals(List.of(new NestedBranches.Branch("P2", Optional.of("S2"), 0, 23)),
                NestedBranches.alterBranches("ALTER P1 TO PROCEED TO P2 OF S2"));
        assertEquals(List.of(new NestedBranches.Branch("9000-ERR", Optional.empty(), 1, 22)),
                NestedBranches.handleBranches(String.join("\n",
                        "EXEC CICS HANDLE CONDITION",
                        "                ERROR(9000-ERR)",
                        "           END-EXEC")));
    }

    /** Every target of the DEPENDING ON form, placed where its GO stands; the words of the next phrase end the list. */
    @Test
    void readsEveryTargetOfAGoToDependingOn() {
        assertEquals(List.of("A-PARA", "B-PARA", "C-PARA"), targets(NestedBranches.goToBranches(
                "READ F1 AT END GO TO A-PARA, B-PARA C-PARA DEPENDING ON WS-X END-READ")));
        assertEquals(List.of(new NestedBranches.Branch("A", Optional.empty(), 0, 15),
                        new NestedBranches.Branch("B", Optional.of("S2"), 0, 15)),
                NestedBranches.goToBranches("READ F1 AT END GO TO A B OF S2 DEPENDING ON X"));
        assertEquals(List.of("X-PARA"), targets(NestedBranches.goToBranches(
                "READ F1 AT END GO TO X-PARA NOT AT END MOVE 1 TO WS-X")));
    }

    @Test
    void readsTheLabelsOfAHandle() {
        assertEquals(List.of("8100-MAPFAIL", "9000-ERROR"), targets(NestedBranches.handleBranches(
                "EXEC CICS HANDLE CONDITION MAPFAIL(8100-MAPFAIL) ERROR(9000-ERROR) END-EXEC")));
        assertEquals(List.of("9000-END"), targets(NestedBranches.handleBranches(
                "EXEC CICS HANDLE AID PF3(9000-END) END-EXEC")));
        assertEquals(List.of(), targets(NestedBranches.handleBranches(
                "EXEC CICS SEND MAP('MAP1') MAPSET('SET1') FROM(WS-MAP) END-EXEC")));
    }

    /** Of a HANDLE ABEND, the LABEL operand is a label; its PROGRAM operand names a program. */
    @Test
    void readsOnlyTheLabelOfAHandleAbend() {
        assertEquals(List.of(), targets(NestedBranches.handleBranches(
                "EXEC CICS HANDLE ABEND PROGRAM(WS-ABEND-PGM) END-EXEC")));
        assertEquals(List.of("9900-ABEND"), targets(NestedBranches.handleBranches(
                "EXEC CICS HANDLE ABEND LABEL(9900-ABEND) END-EXEC")));
    }

    @Test
    void allBranchesListsGoToThenAlterThenHandle() {
        assertEquals(List.of("P1", "P2"), targets(NestedBranches.allBranches(
                "READ F1 AT END GO TO P1 END-READ ALTER SW TO PROCEED TO P2")));
    }

    /**
     * A literal continued onto the next line ends at that line's closing quote, so a GO TO after
     * it is read; the continuation line's own opening quote does not start a literal of its own.
     */
    @Test
    void aContinuedLiteralEndsOnItsContinuationLine() {
        String text = String.join("\n",
                "READ F1 INTO R AT END MOVE 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJ",
                "      -    'XYZ' TO WS-MSG GO TO 9000-EOF DISPLAY 'Z'",
                "           END-READ");
        assertEquals(List.of(new NestedBranches.Branch("9000-EOF", Optional.empty(), 1, 27)),
                NestedBranches.goToBranches(text));
        assertEquals(List.of(), targets(NestedBranches.goToBranches(
                "DISPLAY 'IT''S GO TO X' 'AND GO TO Y'")));
    }
}
