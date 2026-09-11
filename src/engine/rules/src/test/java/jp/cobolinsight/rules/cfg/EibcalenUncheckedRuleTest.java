package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.callgraph.CallGraph;
import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.callgraph.CallGraphNode;
import jp.cobolinsight.core.callgraph.EdgeKind;
import jp.cobolinsight.core.callgraph.NodeKind;
import jp.cobolinsight.core.callgraph.Resolution;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.spi.AnalysisContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R035 synthetic fixture verification for a DFHCOMMAREA reference with no EIBCALEN test. */
class EibcalenUncheckedRuleTest {

    @TempDir
    Path tempDir;

    /** A conversation program (SEND MAP) that reads DFHCOMMAREA and never names EIBCALEN. */
    private static final String NO_TEST = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID. FIX035.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "       01  WS-MAP.",
            /*  6 */ "           05  WS-F PIC X(08).",
            /*  7 */ "       LINKAGE SECTION.",
            /*  8 */ "       01  DFHCOMMAREA.",
            /*  9 */ "           05  CA-KEY PIC X(08).",
            /* 10 */ "       PROCEDURE DIVISION.",
            /* 11 */ "       0000-MAIN.",
            /* 12 */ "           EXEC CICS SEND MAP('MAP1') MAPSET('SET1') FROM(WS-MAP)",
            /* 13 */ "           END-EXEC",
            /* 14 */ "           MOVE CA-KEY TO WS-F",
            /* 15 */ "           GOBACK.",
            "");

    /** Same program, but tests EIBCALEN before reading the communication area. */
    private static final String TESTED = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX035B.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-MAP.",
            "           05  WS-F PIC X(08).",
            "       LINKAGE SECTION.",
            "       01  DFHCOMMAREA.",
            "           05  CA-KEY PIC X(08).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC CICS SEND MAP('MAP1') MAPSET('SET1') FROM(WS-MAP)",
            "           END-EXEC",
            "           IF EIBCALEN = 0",
            "               MOVE SPACES TO WS-F",
            "           ELSE",
            "               MOVE CA-KEY TO WS-F",
            "           END-IF",
            "           GOBACK.",
            "");

    /** A program that hands FIX035 the communication area by XCTL. */
    private static final String XCTL_CALLER = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX035X.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-CA PIC X(08).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC CICS XCTL PROGRAM('FIX035') COMMAREA(WS-CA) LENGTH(8)",
            "           END-EXEC",
            "           GOBACK.",
            "");

    private List<Finding> run(String programId, String text) {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, programId + ".cbl", text);
        return new EibcalenUncheckedRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    private List<Finding> runWithCaller(Optional<CallGraph> callGraph) {
        CobolSemanticModel target = CfgFixtures.parse(tempDir, "FIX035.cbl", NO_TEST);
        CobolSemanticModel caller = CfgFixtures.parse(tempDir, "FIX035X.cbl", XCTL_CALLER);
        return new EibcalenUncheckedRule().evaluate(AnalysisContext.of(
                List.of(target, caller), List.of(), List.of(), List.of(), callGraph, Map.of()));
    }

    @Test
    void ignoresXctlTargetThatNoTransactionStarts() {
        assertEquals(List.of(), runWithCaller(Optional.empty()),
                "XCTL の遷移先は、トランザクションが起動しない限り対象外");
    }

    @Test
    void reportsXctlTargetThatATransactionStarts() {
        CallGraph graph = new CallGraph(
                List.of(new CallGraphNode("transaction:FX35", NodeKind.TRANSACTION, "FX35"),
                        new CallGraphNode("program:FIX035", NodeKind.PROGRAM, "FIX035")),
                List.of(new CallGraphEdge("transaction:FX35", "program:FIX035",
                        EdgeKind.TRANSACTION_TRANSITION, Resolution.CONSTANT)));
        List<Finding> findings = runWithCaller(Optional.of(graph));
        assertEquals(1, findings.size(), () -> "定義表の起動先は XCTL の遷移先でも検出すること: " + findings);
        assertEquals(14, findings.get(0).location().line());
    }

    @Test
    void detectsCommareaReferenceWithNoEibcalenTest() {
        List<Finding> findings = run("FIX035", NO_TEST);
        assertEquals(1, findings.size(), () -> "EIBCALEN 未検査の参照1件を検出すること: " + findings);
        assertEquals("R035", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertEquals(14, findings.get(0).location().line(), "最初に参照する文の行で報告すること");
        assertTrue(findings.get(0).message().contains("DFHCOMMAREA"), findings.get(0).message());
    }

    @Test
    void ignoresProgramThatTestsEibcalen() {
        assertEquals(List.of(), run("FIX035B", TESTED), "EIBCALEN を検査していれば対象外");
    }
}
