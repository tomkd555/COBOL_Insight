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

/** R003 MOVE による桁落ち・切り捨ての合成fixture検証。 */
class MoveTruncationRuleTest {

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
        return new MoveTruncationRule()
                .evaluate(DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsIntegerDigitTruncationBetweenNumericItems() {
        String text = program("F003A",
                "       01  WS-SRC  PIC S9(09)V99 COMP-3.\n"
                        + "       01  WS-DST  PIC 9(06).",
                "           MOVE WS-SRC TO WS-DST",
                "           STOP RUN.");
        List<Finding> findings = run("F003A", text);
        assertEquals(1, findings.size(), () -> "整数部桁落ちを1件検出すること: " + findings);
        assertEquals("R003", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("WS-DST"), findings.get(0).message());
    }

    @Test
    void detectsFractionDigitTruncationBetweenNumericItems() {
        String text = program("F003B",
                "       01  WS-SRC  PIC 9(04)V99.\n"
                        + "       01  WS-DST  PIC 9(04)V9.",
                "           MOVE WS-SRC TO WS-DST",
                "           STOP RUN.");
        List<Finding> findings = run("F003B", text);
        assertEquals(1, findings.size(), () -> "小数部桁落ちを1件検出すること: " + findings);
        assertEquals("R003", findings.get(0).ruleId());
    }

    @Test
    void detectsAlphanumericOverflow() {
        String text = program("F003C",
                "       01  WS-SRC  PIC X(20).\n"
                        + "       01  WS-DST  PIC X(10).",
                "           MOVE WS-SRC TO WS-DST",
                "           STOP RUN.");
        List<Finding> findings = run("F003C", text);
        assertEquals(1, findings.size(), () -> "英数字あふれを1件検出すること: " + findings);
    }

    @Test
    void ignoresEqualWidthMove() {
        String text = program("F003D",
                "       01  WS-SRC  PIC S9(09)V99 COMP-3.\n"
                        + "       01  WS-DST  PIC S9(09)V99 COMP-3.",
                "           MOVE WS-SRC TO WS-DST",
                "           STOP RUN.");
        assertEquals(List.of(), run("F003D", text), "桁数が等しい MOVE は対象外");
    }

    @Test
    void ignoresWideningMove() {
        String text = program("F003E",
                "       01  WS-SRC  PIC 9(05).\n"
                        + "       01  WS-DST  PIC 9(09).",
                "           MOVE WS-SRC TO WS-DST",
                "           STOP RUN.");
        assertEquals(List.of(), run("F003E", text), "受信が送信より大きい MOVE は対象外");
    }

    @Test
    void ignoresLiteralAndFigurativeConstantSenders() {
        String text = program("F003F",
                "       01  WS-DST  PIC 9(03).",
                "           MOVE ZERO TO WS-DST",
                "           MOVE 123456 TO WS-DST",
                "           STOP RUN.");
        assertEquals(List.of(), run("F003F", text), "図形定数・リテラル送信は対象外");
    }

    @Test
    void ignoresSubscriptVariableOfReceiver() {
        String text = program("F003H",
                "       01  WS-SRC  PIC 9(05).\n"
                        + "       01  WS-IDX  PIC 9(02) COMP.\n"
                        + "       01  WS-TBL.\n"
                        + "           05  WS-ENT  PIC 9(05) OCCURS 10 TIMES.",
                "           MOVE 1 TO WS-IDX",
                "           MOVE WS-SRC TO WS-ENT(WS-IDX)",
                "           STOP RUN.");
        assertEquals(List.of(), run("F003H", text), "受信側の添字に使う変数は受信項目ではない");
    }

    @Test
    void ignoresGroupItemSender() {
        String text = program("F003G",
                "       01  WS-GRP.\n"
                        + "           05  WS-A  PIC X(10).\n"
                        + "           05  WS-B  PIC X(10).\n"
                        + "       01  WS-DST  PIC X(05).",
                "           MOVE WS-GRP TO WS-DST",
                "           STOP RUN.");
        assertEquals(List.of(), run("F003G", text), "集団項目送信は PIC 未解決のため対象外");
    }
}
