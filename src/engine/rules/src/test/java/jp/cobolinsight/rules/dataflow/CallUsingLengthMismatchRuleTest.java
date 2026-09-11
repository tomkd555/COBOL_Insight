package jp.cobolinsight.rules.dataflow;

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
import jp.cobolinsight.rules.SourceTextIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic-fixture verification for R041, a CALL/LINKAGE USING mismatch. */
class CallUsingLengthMismatchRuleTest {

    @TempDir
    Path tempDir;

    /** Builds a SEMANTIC+CALL_GRAPH-stage AnalysisContext with one CALL edge from caller to callee. */
    private List<Finding> run(CobolSemanticModel caller, String callerText, int callLine,
            CobolSemanticModel callee, String calleeText) {
        Map<String, String> texts = Map.of(caller.sourceFile(), callerText,
                callee.sourceFile(), calleeText);
        String callerId = "program:" + caller.programId().toUpperCase(Locale.ROOT);
        String calleeId = "program:" + callee.programId().toUpperCase(Locale.ROOT);
        CallGraph graph = new CallGraph(
                List.of(new CallGraphNode(callerId, NodeKind.PROGRAM, caller.programId()),
                        new CallGraphNode(calleeId, NodeKind.PROGRAM, callee.programId())),
                List.of(new CallGraphEdge(callerId, calleeId, EdgeKind.CALL, Resolution.CONSTANT, 1,
                        callLine)));
        AnalysisContext context = AnalysisContext.of(List.of(caller, callee), List.of(), List.of(),
                List.of(), Optional.of(graph), Map.of(SourceTextIndex.class, new SourceTextIndex(texts)));
        return new CallUsingLengthMismatchRule().evaluate(context);
    }

    private static final String CALLEE_LONGER = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. F041T.",
            "       DATA DIVISION.",
            "       LINKAGE SECTION.",
            "       01  LK-AREA  PIC X(10).",
            "       PROCEDURE DIVISION USING LK-AREA.",
            "       0000-MAIN.",
            "           GOBACK.",
            "");

    private static final String CALLER_SHORT = String.join("\n",
            /* 1 */ "       IDENTIFICATION DIVISION.",
            /* 2 */ "       PROGRAM-ID. F041A.",
            /* 3 */ "       DATA DIVISION.",
            /* 4 */ "       WORKING-STORAGE SECTION.",
            /* 5 */ "       01  WS-SHORT-AREA  PIC X(05).",
            /* 6 */ "       PROCEDURE DIVISION.",
            /* 7 */ "       0000-MAIN.",
            /* 8 */ "           CALL 'F041T' USING WS-SHORT-AREA",
            /* 9 */ "           STOP RUN.",
            "");

    private static final String CALLER_EXACT = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. F041B.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-EXACT-AREA  PIC X(10).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           CALL 'F041T' USING WS-EXACT-AREA",
            "           STOP RUN.",
            "");

    private static final String CALLER_BY_VALUE = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. F041C.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-SHORT-AREA  PIC X(05).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           CALL 'F041T' USING BY VALUE WS-SHORT-AREA",
            "           STOP RUN.",
            "");

    private static final String CALLEE_TWO_PARAMS = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. F041U.",
            "       DATA DIVISION.",
            "       LINKAGE SECTION.",
            "       01  LK-A  PIC X(05).",
            "       01  LK-B  PIC X(05).",
            "       PROCEDURE DIVISION USING LK-A LK-B.",
            "       0000-MAIN.",
            "           GOBACK.",
            "");

    private static final String CALLER_ONE_OPERAND = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. F041D.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-AREA  PIC X(05).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           CALL 'F041U' USING WS-AREA",
            "           STOP RUN.",
            "");

    @Test
    void detectsPassedItemShorterThanTheCalleesLinkageItem() {
        CobolSemanticModel callee = DataFlowFixtures.parse(tempDir, "F041T.cbl", CALLEE_LONGER);
        CobolSemanticModel caller = DataFlowFixtures.parse(tempDir, "F041A.cbl", CALLER_SHORT);
        List<Finding> findings = run(caller, CALLER_SHORT, 8, callee, CALLEE_LONGER);
        assertEquals(1, findings.size(), () -> "長さ不一致1件を検出すること: " + findings);
        assertEquals("R041", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(8, findings.get(0).location().line());
        assertTrue(findings.get(0).message().contains("F041T"), findings.get(0).message());
        assertTrue(findings.get(0).message().contains("LK-AREA"), findings.get(0).message());
    }

    @Test
    void ignoresCallWhenThePassedItemIsLongEnough() {
        CobolSemanticModel callee = DataFlowFixtures.parse(tempDir, "F041T.cbl", CALLEE_LONGER);
        CobolSemanticModel caller = DataFlowFixtures.parse(tempDir, "F041B.cbl", CALLER_EXACT);
        assertEquals(List.of(), run(caller, CALLER_EXACT, 8, callee, CALLEE_LONGER),
                "渡す項目が呼び出し先の連絡節項目以上の長さなら対象外");
    }

    @Test
    void detectsOperandCountMismatch() {
        CobolSemanticModel callee = DataFlowFixtures.parse(tempDir, "F041U.cbl", CALLEE_TWO_PARAMS);
        CobolSemanticModel caller = DataFlowFixtures.parse(tempDir, "F041D.cbl", CALLER_ONE_OPERAND);
        List<Finding> findings = run(caller, CALLER_ONE_OPERAND, 8, callee, CALLEE_TWO_PARAMS);
        assertEquals(1, findings.size(), () -> "個数不一致1件を検出すること: " + findings);
        assertEquals("R041", findings.get(0).ruleId());
        assertTrue(findings.get(0).message().contains("F041U"), findings.get(0).message());
    }

    @Test
    void ignoresCallThatPassesByValue() {
        CobolSemanticModel callee = DataFlowFixtures.parse(tempDir, "F041T.cbl", CALLEE_LONGER);
        CobolSemanticModel caller = DataFlowFixtures.parse(tempDir, "F041C.cbl", CALLER_BY_VALUE);
        assertEquals(List.of(), run(caller, CALLER_BY_VALUE, 8, callee, CALLEE_LONGER),
                "BY VALUE を含む CALL 文は対象外");
    }
}
