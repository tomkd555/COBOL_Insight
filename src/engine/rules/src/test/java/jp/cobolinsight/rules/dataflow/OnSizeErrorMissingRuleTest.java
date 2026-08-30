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

/** R004 synthetic fixture verification for a missing ON SIZE ERROR clause. */
class OnSizeErrorMissingRuleTest {

    @TempDir
    Path tempDir;

    private static String program(String programId, String items, String... procedure) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-A    PIC 9(05).",
                "       01  WS-B    PIC 9(05).",
                items));
        sb.append("\n");
        sb.append(String.join("\n",
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
        return new OnSizeErrorMissingRule()
                .evaluate(DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsComputeOverflowWithoutOnSizeError() {
        String text = program("F004A", "       01  WS-R    PIC 9(03).",
                "           COMPUTE WS-R = WS-A * WS-B",
                "           DISPLAY WS-R",
                "           STOP RUN.");
        List<Finding> findings = run("F004A", text);
        assertEquals(1, findings.size(), () -> "けたあふれ可能な COMPUTE を1件検出すること: " + findings);
        assertEquals("R004", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
    }

    @Test
    void detectsGivingFormOverflow() {
        String text = program("F004B", "       01  WS-R    PIC 9(03).",
                "           MULTIPLY WS-A BY WS-B GIVING WS-R",
                "           DISPLAY WS-R",
                "           STOP RUN.");
        List<Finding> findings = run("F004B", text);
        assertEquals(1, findings.size(), () -> "GIVING 形式のけたあふれを1件検出すること: " + findings);
        assertEquals("R004", findings.get(0).ruleId());
    }

    @Test
    void ignoresWhenOnSizeErrorPresent() {
        String text = program("F004C", "       01  WS-R    PIC 9(03).",
                "           COMPUTE WS-R = WS-A * WS-B",
                "               ON SIZE ERROR MOVE ZERO TO WS-R",
                "           END-COMPUTE",
                "           STOP RUN.");
        assertEquals(List.of(), run("F004C", text), "ON SIZE ERROR がある算術は対象外");
    }

    @Test
    void ignoresInPlaceAccumulation() {
        String text = program("F004D", "       01  WS-CNT  PIC 9(03) VALUE ZERO.",
                "           ADD 1 TO WS-CNT",
                "           DISPLAY WS-CNT",
                "           STOP RUN.");
        assertEquals(List.of(), run("F004D", text), "受信を被加算に含む累算は対象外");
    }

    @Test
    void ignoresNonAccumulationThatFits() {
        String text = program("F004E", "       01  WS-BIG  PIC 9(07).",
                "           COMPUTE WS-BIG = WS-A + WS-B",
                "           DISPLAY WS-BIG",
                "           STOP RUN.");
        assertEquals(List.of(), run("F004E", text), "結果が受信容量に収まる算術は対象外");
    }
}
