package jp.cobolinsight.core.semantic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The branches a statement's text carries: nested GO TOs, ALTER pairs and HANDLE labels. */
class NestedBranchesTest {

    @Test
    void readsEveryPairOfAnAlter() {
        assertEquals(List.of("3300-SECOND", "3400-THIRD"), NestedBranches.alterTargets(
                "ALTER 3100-SW TO PROCEED TO 3300-SECOND, 3200-SW TO PROCEED TO 3400-THIRD"));
        assertEquals(List.of("3300-SECOND"), NestedBranches.alterTargets("ALTER 3100-SW TO 3300-SECOND"));
        assertEquals(List.of(), NestedBranches.alterTargets("MOVE 'ALTER X TO Y' TO WS-A"));
    }

    /** The span of a multi-line statement can hold a comment line; column 7 of a later line says so. */
    @Test
    void ignoresACommentedOutBranchInsideAStatement() {
        String text = String.join("\n",
                "READ FLT-FILE",
                "      *    AT END GO TO 9900-OLD-EOF",
                "           AT END GO TO 9000-EOF");
        assertEquals(List.of("9000-EOF"), NestedBranches.goToTargets(text));
    }

    @Test
    void readsTheLabelsOfAHandle() {
        assertEquals(List.of("8100-MAPFAIL", "9000-ERROR"), NestedBranches.handleTargets(
                "EXEC CICS HANDLE CONDITION MAPFAIL(8100-MAPFAIL) ERROR(9000-ERROR) END-EXEC"));
        assertEquals(List.of("9000-END"), NestedBranches.handleTargets(
                "EXEC CICS HANDLE AID PF3(9000-END) END-EXEC"));
        assertEquals(List.of(), NestedBranches.handleTargets(
                "EXEC CICS SEND MAP('MAP1') MAPSET('SET1') FROM(WS-MAP) END-EXEC"));
    }
}
