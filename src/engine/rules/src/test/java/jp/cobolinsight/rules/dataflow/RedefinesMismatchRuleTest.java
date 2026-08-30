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

/** R015 synthetic fixture verification for REDEFINES length/boundary mismatches. */
class RedefinesMismatchRuleTest {

    @TempDir
    Path tempDir;

    private static String program(String programId, String items) {
        return String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                items,
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           STOP RUN.") + "\n";
    }

    private List<Finding> run(String programId, String text) {
        CobolSemanticModel model = DataFlowFixtures.parse(tempDir, programId + ".cbl", text);
        return new RedefinesMismatchRule()
                .evaluate(DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsElementaryRedefinerLargerThanOriginal() {
        String text = program("F015A",
                "       01  WS-REC.\n"
                        + "           05  WS-ORIG    PIC X(04).\n"
                        + "           05  WS-REDEF REDEFINES WS-ORIG PIC X(08).");
        List<Finding> findings = run("F015A", text);
        assertEquals(1, findings.size(), () -> "元項目より大きい再定義を1件検出すること: " + findings);
        assertEquals("R015", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("WS-REDEF"), findings.get(0).message());
    }

    @Test
    void detectsGroupRedefinerLargerThanOriginal() {
        String text = program("F015B",
                "       01  WS-REC.\n"
                        + "           05  WS-ORIG    PIC X(04).\n"
                        + "           05  WS-REDEF REDEFINES WS-ORIG.\n"
                        + "               10  WS-R1  PIC X(04).\n"
                        + "               10  WS-R2  PIC X(04).");
        List<Finding> findings = run("F015B", text);
        assertEquals(1, findings.size(), () -> "集団項目の再定義超過を1件検出すること: " + findings);
        assertEquals("R015", findings.get(0).ruleId());
    }

    @Test
    void ignoresEqualLengthRedefine() {
        String text = program("F015C",
                "       01  WS-REC.\n"
                        + "           05  WS-ORIG    PIC X(08).\n"
                        + "           05  WS-REDEF REDEFINES WS-ORIG PIC 9(08).");
        assertEquals(List.of(), run("F015C", text), "同じ長さの再定義は対象外");
    }

    @Test
    void ignoresSmallerRedefine() {
        String text = program("F015D",
                "       01  WS-REC.\n"
                        + "           05  WS-ORIG    PIC X(08).\n"
                        + "           05  WS-REDEF REDEFINES WS-ORIG PIC X(04).");
        assertEquals(List.of(), run("F015D", text), "元項目より小さい再定義は正当なため対象外");
    }
}
