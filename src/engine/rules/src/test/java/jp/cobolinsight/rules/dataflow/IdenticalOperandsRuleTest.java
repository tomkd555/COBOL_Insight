package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Synthetic-fixture verification for R025, expressions where both sides of a binary operator are identical. */
class IdenticalOperandsRuleTest {

    @TempDir
    Path tempDir;

    private static String program(String programId, String... procedure) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-A    PIC 9(03).",
                "       01  WS-B    PIC 9(03).",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA."));
        sb.append("\n");
        for (String line : procedure) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private List<Finding> run(String programId, String text) {
        CobolSemanticModel model = DataFlowFixtures.parse(tempDir, programId + ".cbl", text);
        return new IdenticalOperandsRule()
                .evaluate(DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsAlwaysTrueEquality() {
        String text = program("F025A",
                "           IF WS-A = WS-A",
                "               DISPLAY 'EQ'",
                "           END-IF",
                "           STOP RUN.");
        List<Finding> findings = run("F025A", text);
        assertEquals(1, findings.size(), () -> "常真の比較を1件検出すること: " + findings);
        assertEquals("R025", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
    }

    @Test
    void detectsAlwaysFalseComparison() {
        String text = program("F025B",
                "           IF WS-A > WS-A",
                "               DISPLAY 'GT'",
                "           END-IF",
                "           STOP RUN.");
        List<Finding> findings = run("F025B", text);
        assertEquals(1, findings.size(), () -> "常偽の比較を1件検出すること: " + findings);
        assertEquals("R025", findings.get(0).ruleId());
    }

    @Test
    void detectsMeaninglessSubtractionInCompute() {
        String text = program("F025C",
                "           COMPUTE WS-A = WS-B - WS-B",
                "           STOP RUN.");
        List<Finding> findings = run("F025C", text);
        assertEquals(1, findings.size(), () -> "無意味な減算を1件検出すること: " + findings);
        assertEquals("R025", findings.get(0).ruleId());
    }

    @Test
    void ignoresDistinctOperands() {
        String text = program("F025D",
                "           IF WS-A = WS-B",
                "               DISPLAY 'NE'",
                "           END-IF",
                "           STOP RUN.");
        assertEquals(List.of(), run("F025D", text), "両辺が異なる比較は対象外");
    }

    @Test
    void ignoresComputeAssignmentWithDistinctRhs() {
        String text = program("F025E",
                "           COMPUTE WS-A = WS-B + WS-B",
                "           STOP RUN.");
        assertEquals(List.of(), run("F025E", text),
                "代入の左辺と右辺の一致(A = A + ...)や加算は対象外");
    }
}
