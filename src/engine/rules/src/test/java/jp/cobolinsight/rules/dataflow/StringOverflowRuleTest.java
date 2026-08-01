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

/** R016 STRING/UNSTRING の受信領域あふれの合成fixture検証。 */
class StringOverflowRuleTest {

    @TempDir
    Path tempDir;

    private static String program(String programId, String items, String... procedure) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION."));
        sb.append("\n").append(items).append("\n");
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
        return new StringOverflowRule()
                .evaluate(DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsStringConcatenationOverflow() {
        String text = program("F016A",
                "       01  WS-SRC  PIC X(20).\n"
                        + "       01  WS-DST  PIC X(10).",
                "           STRING 'PREFIX-' WS-SRC DELIMITED BY SIZE",
                "               INTO WS-DST",
                "           END-STRING",
                "           STOP RUN.");
        List<Finding> findings = run("F016A", text);
        assertEquals(1, findings.size(), () -> "STRING の受信あふれを1件検出すること: " + findings);
        assertEquals("R016", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("WS-DST"), findings.get(0).message());
    }

    @Test
    void ignoresStringThatFits() {
        String text = program("F016B",
                "       01  WS-SRC  PIC X(05).\n"
                        + "       01  WS-DST  PIC X(10).",
                "           STRING 'AB' WS-SRC DELIMITED BY SIZE",
                "               INTO WS-DST",
                "           END-STRING",
                "           STOP RUN.");
        assertEquals(List.of(), run("F016B", text), "合計長が受信長以下の STRING は対象外");
    }

    @Test
    void detectsUnstringOverflow() {
        String text = program("F016C",
                "       01  WS-SRC  PIC X(30).\n"
                        + "       01  WS-A    PIC X(05).\n"
                        + "       01  WS-B    PIC X(05).",
                "           UNSTRING WS-SRC INTO WS-A WS-B",
                "           END-UNSTRING",
                "           STOP RUN.");
        List<Finding> findings = run("F016C", text);
        assertEquals(1, findings.size(), () -> "UNSTRING の受信あふれを1件検出すること: " + findings);
        assertEquals("R016", findings.get(0).ruleId());
        assertTrue(findings.get(0).message().contains("WS-SRC"), findings.get(0).message());
    }

    @Test
    void ignoresUnstringThatFits() {
        String text = program("F016D",
                "       01  WS-SRC  PIC X(08).\n"
                        + "       01  WS-A    PIC X(05).\n"
                        + "       01  WS-B    PIC X(05).",
                "           UNSTRING WS-SRC INTO WS-A WS-B",
                "           END-UNSTRING",
                "           STOP RUN.");
        assertEquals(List.of(), run("F016D", text), "送信長が受信合計長以下の UNSTRING は対象外");
    }
}
