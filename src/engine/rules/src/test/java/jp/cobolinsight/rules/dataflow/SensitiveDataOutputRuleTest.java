package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R027 機密データ項目のマスキングなし出力の合成fixture検証。 */
class SensitiveDataOutputRuleTest {

    @TempDir
    Path tempDir;

    private static String program(String programId, String... procedure) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-CUST-SSN   PIC X(11).",
                "       01  WS-MASKED     PIC X(11).",
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
        return new SensitiveDataOutputRule()
                .evaluate(DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsSensitiveItemDisplayedWithoutMasking() {
        String text = program("F027A",
                "           ACCEPT WS-CUST-SSN",
                "           DISPLAY WS-CUST-SSN",
                "           STOP RUN.");
        List<Finding> findings = run("F027A", text);
        assertEquals(1, findings.size(), () -> "機密項目のマスキングなし出力を1件検出すること: " + findings);
        assertEquals("R027", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("WS-CUST-SSN"), findings.get(0).message());
    }

    @Test
    void ignoresMaskedValueOutput() {
        String text = program("F027B",
                "           MOVE '***-**-****' TO WS-MASKED",
                "           DISPLAY WS-MASKED",
                "           STOP RUN.");
        assertEquals(List.of(), run("F027B", text),
                "リテラルで値を差し替えた変数の出力は対象外(汚染が絶たれる)");
    }
}
