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

/** R028 符号なし項目への負値算出の合成fixture検証。 */
class UnsignedNegativeResultRuleTest {

    @TempDir
    Path tempDir;

    private static String program(String programId, String receiverDecl, String... procedure) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-A    PIC 9(03).",
                "       01  WS-B    PIC 9(03).",
                "       " + receiverDecl,
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
        return new UnsignedNegativeResultRule()
                .evaluate(DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsComputeResultThatMayBeNegativeIntoUnsignedReceiver() {
        String text = program("F028A", "01  WS-UNS  PIC 9(05).",
                "           COMPUTE WS-UNS = WS-A - WS-B",
                "           DISPLAY WS-UNS",
                "           STOP RUN.");
        List<Finding> findings = run("F028A", text);
        assertEquals(1, findings.size(), () -> "符号なし受信への負値算出を1件検出すること: " + findings);
        assertEquals("R028", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("WS-UNS"), findings.get(0).message());
    }

    @Test
    void detectsSubtractResultThatMayBeNegativeIntoUnsignedReceiver() {
        String text = program("F028B", "01  WS-UNS  PIC 9(05).",
                "           SUBTRACT WS-A FROM WS-B GIVING WS-UNS",
                "           DISPLAY WS-UNS",
                "           STOP RUN.");
        List<Finding> findings = run("F028B", text);
        assertEquals(1, findings.size(), () -> "SUBTRACT の負値算出を1件検出すること: " + findings);
        assertEquals("R028", findings.get(0).ruleId());
    }

    @Test
    void ignoresSignedReceiver() {
        String text = program("F028C", "01  WS-SIGNED  PIC S9(05).",
                "           COMPUTE WS-SIGNED = WS-A - WS-B",
                "           DISPLAY WS-SIGNED",
                "           STOP RUN.");
        assertEquals(List.of(), run("F028C", text), "符号付き受信項目は対象外");
    }

    @Test
    void ignoresNonNegativeResult() {
        String text = program("F028D", "01  WS-UNS  PIC 9(05).",
                "           COMPUTE WS-UNS = WS-A + WS-B",
                "           DISPLAY WS-UNS",
                "           STOP RUN.");
        assertEquals(List.of(), run("F028D", text), "結果が負になり得ない演算は対象外");
    }
}
