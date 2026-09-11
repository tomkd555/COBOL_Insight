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

/**
 * Synthetic-fixture verification for R049, an arithmetic or numeric-relation operand that is a
 * FILE/LINKAGE USAGE DISPLAY numeric item with no NUMERIC test.
 */
class UntestedNumericInputArithmeticRuleTest {

    @TempDir
    Path tempDir;

    private static final String FD_PROGRAM_HEADER = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. %s.",
            "       ENVIRONMENT DIVISION.",
            "       INPUT-OUTPUT SECTION.",
            "       FILE-CONTROL.",
            "           SELECT KEIYAKU ASSIGN TO KEIYAKU.",
            "       DATA DIVISION.",
            "       FILE SECTION.",
            "       FD  KEIYAKU",
            "           LABEL RECORDS ARE STANDARD.",
            "       01  IN-REC.",
            "           05  IN-金額  PIC 9(07).",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-合計  PIC 9(09).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.");

    private static String fdProgram(String programId, String... procedure) {
        StringBuilder sb = new StringBuilder(String.format(FD_PROGRAM_HEADER, programId));
        sb.append("\n");
        for (String line : procedure) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private List<Finding> run(String programId, String text) {
        CobolSemanticModel model = DataFlowFixtures.parse(tempDir, programId + ".cbl", text);
        return new UntestedNumericInputArithmeticRule()
                .evaluate(DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsFileRecordItemAddedWithoutNumericTest() {
        String text = fdProgram("F049A",
                "           ADD IN-金額 TO WS-合計",
                "           STOP RUN.");
        List<Finding> findings = run("F049A", text);
        assertEquals(1, findings.size(), () -> "字類未検査の演算を1件検出すること: " + findings);
        assertEquals("R049", findings.get(0).ruleId());
        assertEquals(FindingLevel.NOTE, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("IN-金額"), findings.get(0).message());
    }

    @Test
    void detectsFileRecordItemComparedWithoutNumericTest() {
        String text = fdProgram("F049B",
                "           IF IN-金額 > ZERO",
                "               ADD 1 TO WS-合計",
                "           END-IF",
                "           STOP RUN.");
        List<Finding> findings = run("F049B", text);
        assertEquals(1, findings.size(), () -> "字類未検査の比較を1件検出すること: " + findings);
        assertEquals("R049", findings.get(0).ruleId());
    }

    @Test
    void ignoresFileRecordItemTestedBeforeUse() {
        String text = fdProgram("F049C",
                "           IF IN-金額 IS NUMERIC",
                "               ADD IN-金額 TO WS-合計",
                "           END-IF",
                "           STOP RUN.");
        assertEquals(List.of(), run("F049C", text), "NUMERIC で検査済みの項目は対象外");
    }

    @Test
    void ignoresWorkingStorageOperand() {
        String text = fdProgram("F049D",
                "           ADD WS-合計 TO WS-合計",
                "           STOP RUN.");
        assertEquals(List.of(), run("F049D", text), "作業場所節の項目は対象外");
    }
}
