package jp.cobolinsight.rules.cfg;

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

/** R017 boundary fixture verification for unchecked file status (not detected when checked, detected when not checked). */
class FileStatusUncheckedRuleTest {

    @TempDir
    Path tempDir;

    private static String source(String programId, boolean checkStatus) {
        return String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       ENVIRONMENT DIVISION.",
                "       INPUT-OUTPUT SECTION.",
                "       FILE-CONTROL.",
                "           SELECT MYFILE ASSIGN TO MYFILE",
                "                  ORGANIZATION IS SEQUENTIAL",
                "                  FILE STATUS IS WS-ST.",
                "       DATA DIVISION.",
                "       FILE SECTION.",
                "       FD  MYFILE",
                "           LABEL RECORDS ARE STANDARD.",
                "       01  MY-REC PIC X(80).",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-ST  PIC X(02).",
                "       01  WS-EOF PIC X(01) VALUE 'N'.",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           OPEN INPUT MYFILE",
                "           READ MYFILE",
                "               AT END MOVE 'Y' TO WS-EOF",
                "           END-READ",
                checkStatus
                        ? "           IF WS-ST NOT = '00'\n"
                                + "               DISPLAY 'IO ERROR'\n"
                                + "           END-IF"
                        : "           DISPLAY 'READ DONE'",
                "           CLOSE MYFILE",
                "           STOP RUN.",
                "");
    }

    @Test
    void detectsReadWhoseFileStatusIsNeverChecked() {
        String text = source("FIX017", false);
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX017.cbl", text);
        List<Finding> findings = new FileStatusUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(1, findings.size(), () -> "未検査READ1件を検出すること: " + findings);
        assertEquals("R017", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(20, findings.get(0).location().line());
        assertTrue(findings.get(0).message().contains("WS-ST"), findings.get(0).message());
    }

    @Test
    void ignoresReadWhoseFileStatusIsCheckedAhead() {
        String text = source("FIX017B", true);
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX017B.cbl", text);
        List<Finding> findings = new FileStatusUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(List.of(), findings,
                () -> "後続でFILE STATUSを条件参照するREADは対象外: " + findings);
    }
}
