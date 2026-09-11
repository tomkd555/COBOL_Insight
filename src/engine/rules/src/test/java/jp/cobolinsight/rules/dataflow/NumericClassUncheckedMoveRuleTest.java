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

/** Synthetic-fixture verification for R034, a MOVE into a numeric item with an untested alphanumeric sender. */
class NumericClassUncheckedMoveRuleTest {

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
        sb.append("       PROCEDURE DIVISION.\n       MAIN-PARA.\n");
        for (String line : procedure) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private List<Finding> run(String programId, String text) {
        CobolSemanticModel model = DataFlowFixtures.parse(tempDir, programId + ".cbl", text);
        return new NumericClassUncheckedMoveRule()
                .evaluate(DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsMoveFromUntestedAlphanumericIntoNumeric() {
        String text = program("F034A",
                "       01  WS-IN-X  PIC X(09).\n"
                        + "       01  WS-IN-9  PIC 9(09).",
                "           MOVE WS-IN-X TO WS-IN-9",
                "           STOP RUN.");
        List<Finding> findings = run("F034A", text);
        assertEquals(1, findings.size(), () -> "字類未検査の転記を1件検出すること: " + findings);
        assertEquals("R034", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("WS-IN-X"), findings.get(0).message());
        assertTrue(findings.get(0).message().contains("WS-IN-9"), findings.get(0).message());
    }

    @Test
    void ignoresMoveWhoseSenderIsTestedBeforeIt() {
        String text = program("F034B",
                "       01  WS-IN-X  PIC X(09).\n"
                        + "       01  WS-IN-9  PIC 9(09).",
                "           IF WS-IN-X IS NUMERIC",
                "               MOVE WS-IN-X TO WS-IN-9",
                "           END-IF",
                "           STOP RUN.");
        assertEquals(List.of(), run("F034B", text), "送り出し側項目を検査済みの MOVE は対象外");
    }

    @Test
    void ignoresMoveWhoseSenderIsTestedElsewhereInTheProgram() {
        String text = program("F034C",
                "       01  WS-IN-X  PIC X(09).\n"
                        + "       01  WS-IN-9  PIC 9(09).",
                "           MOVE WS-IN-X TO WS-IN-9",
                "           IF WS-IN-X NOT NUMERIC",
                "               DISPLAY 'bad'",
                "           END-IF",
                "           STOP RUN.");
        assertEquals(List.of(), run("F034C", text),
                "検査条件が別の場所にあっても原始プログラム全体を見て対象外にすること");
    }

    @Test
    void ignoresNumericToNumericMove() {
        String text = program("F034D",
                "       01  WS-IN-9A  PIC 9(05).\n"
                        + "       01  WS-IN-9B  PIC 9(09).",
                "           MOVE WS-IN-9A TO WS-IN-9B",
                "           STOP RUN.");
        assertEquals(List.of(), run("F034D", text), "送り出し側が数字項目の MOVE は対象外");
    }

    @Test
    void ignoresLiteralSender() {
        String text = program("F034E",
                "       01  WS-IN-9  PIC 9(03).",
                "           MOVE ZERO TO WS-IN-9",
                "           STOP RUN.");
        assertEquals(List.of(), run("F034E", text), "表意定数の送り出しは対象外");
    }

    @Test
    void ignoresGroupItemSender() {
        String text = program("F034F",
                "       01  WS-GRP.\n"
                        + "           05  WS-A  PIC X(05).\n"
                        + "       01  WS-IN-9  PIC 9(05).",
                "           MOVE WS-GRP TO WS-IN-9",
                "           STOP RUN.");
        assertEquals(List.of(), run("F034F", text), "集団項目送信は PIC 未解決のため対象外");
    }
}
