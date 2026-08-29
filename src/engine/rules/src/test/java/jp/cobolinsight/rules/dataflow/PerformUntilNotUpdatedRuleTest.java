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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R012 終了条件が更新されない PERFORM UNTIL の合成fixture検証。 */
class PerformUntilNotUpdatedRuleTest {

    @TempDir
    Path tempDir;

    private static String program(String programId, String... procedure) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-DONE-FLAG  PIC X(01) VALUE 'N'.",
                "       01  WS-COUNTER    PIC 9(03) VALUE ZERO.",
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
        return new PerformUntilNotUpdatedRule()
                .evaluate(DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsInlineLoopWhoseConditionVariableIsNeverUpdated() {
        String text = program("F012A",
                "           PERFORM UNTIL WS-DONE-FLAG = 'Y'",
                "               ADD 1 TO WS-COUNTER",
                "               DISPLAY WS-COUNTER",
                "           END-PERFORM",
                "           STOP RUN.");
        List<Finding> findings = run("F012A", text);
        assertEquals(1, findings.size(), () -> "更新されない終了条件を1件検出すること: " + findings);
        assertEquals("R012", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("WS-DONE-FLAG"), findings.get(0).message());
    }

    @Test
    void ignoresInlineLoopWhoseConditionVariableIsUpdatedInBody() {
        String text = program("F012B",
                "           PERFORM UNTIL WS-DONE-FLAG = 'Y'",
                "               ADD 1 TO WS-COUNTER",
                "               IF WS-COUNTER > 10",
                "                   MOVE 'Y' TO WS-DONE-FLAG",
                "               END-IF",
                "           END-PERFORM",
                "           STOP RUN.");
        assertEquals(List.of(), run("F012B", text),
                "ループ本体で終了条件変数を更新する場合は対象外");
    }

    @Test
    void detectsParagraphPerformWhoseConditionVariableIsNeverUpdated() {
        String text = program("F012C",
                "           PERFORM SUB-PARA UNTIL WS-DONE-FLAG = 'Y'",
                "           STOP RUN.",
                "       SUB-PARA.",
                "           ADD 1 TO WS-COUNTER.");
        List<Finding> findings = run("F012C", text);
        assertEquals(1, findings.size(), () -> "段落 PERFORM UNTIL の未更新を検出すること: " + findings);
        assertEquals("R012", findings.get(0).ruleId());
    }

    @Test
    void ignoresParagraphPerformWhoseConditionVariableIsUpdatedInCalledParagraph() {
        String text = program("F012D",
                "           PERFORM SUB-PARA UNTIL WS-DONE-FLAG = 'Y'",
                "           STOP RUN.",
                "       SUB-PARA.",
                "           ADD 1 TO WS-COUNTER",
                "           IF WS-COUNTER > 10",
                "               MOVE 'Y' TO WS-DONE-FLAG",
                "           END-IF.");
        assertEquals(List.of(), run("F012D", text),
                "呼び出す段落で終了条件変数を更新する場合は対象外");
    }
}
