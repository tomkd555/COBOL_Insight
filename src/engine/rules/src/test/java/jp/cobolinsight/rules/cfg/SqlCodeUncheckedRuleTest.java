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

/** R018 boundary fixture verification for unchecked SQLCODE (not detected when checked, detected when unchecked; SELECT INTO is excluded). */
class SqlCodeUncheckedRuleTest {

    @TempDir
    Path tempDir;

    private static String source(String programId, boolean checkSqlCode) {
        return String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                "       01  WS-D PIC 9(01).",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           EXEC SQL",
                "               UPDATE MYTAB SET COL1 = 1",
                "           END-EXEC",
                checkSqlCode
                        ? "           IF SQLCODE NOT = 0\n"
                                + "               DISPLAY 'SQL ERROR'\n"
                                + "           END-IF"
                        : "           DISPLAY 'UPDATE DONE'",
                "           GOBACK.",
                "");
    }

    // Confirms that SELECT INTO is excluded (only data-modifying DML is in scope).
    private static final String SELECT_INTO = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX018C.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "           EXEC SQL INCLUDE SQLCA END-EXEC.",
            "       01  WS-V PIC S9(04) COMP.",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC SQL",
            "               SELECT COL1 INTO :WS-V FROM MYTAB",
            "           END-EXEC",
            "           DISPLAY 'SELECT DONE'",
            "           GOBACK.",
            "");

    @Test
    void detectsUpdateWhoseSqlCodeIsNeverChecked() {
        String text = source("FIX018", false);
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018.cbl", text);
        List<Finding> findings = new SqlCodeUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(1, findings.size(), () -> "未検査UPDATE1件を検出すること: " + findings);
        assertEquals("R018", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(11, findings.get(0).location().line());
    }

    @Test
    void ignoresUpdateFollowedBySqlCodeCheck() {
        String text = source("FIX018B", true);
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018B.cbl", text);
        List<Finding> findings = new SqlCodeUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(List.of(), findings, () -> "SQLCODE検査つきUPDATEは対象外: " + findings);
    }

    @Test
    void ignoresSelectInto() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018C.cbl", SELECT_INTO);
        List<Finding> findings = new SqlCodeUncheckedRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), SELECT_INTO)));
        assertEquals(List.of(), findings,
                () -> "SELECT INTOはデータ変更DMLでないため対象外: " + findings);
    }
}
