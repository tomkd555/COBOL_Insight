package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R037 synthetic fixtures: a nullable DCLGEN column received with and without an indicator. */
class NullIndicatorMissingRuleTest {

    @TempDir
    Path tempDir;

    private static String program(String name, String intoClause) {
        return String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + name + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                "           EXEC SQL DECLARE MYDB.KEIYAKU TABLE",
                "           ( KEIYAKU_NO      CHAR(10) NOT NULL,",
                "             SHORI_KBN       CHAR(1)",
                "           ) END-EXEC.",
                "       01  WS-KEIYAKU-NO     PIC X(10).",
                "       01  WS-SHORI-KBN      PIC X(01).",
                "       01  WS-SHORI-KBN-IND  PIC S9(04) COMP.",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           EXEC SQL",
                "               SELECT KEIYAKU_NO, SHORI_KBN",
                "                 INTO " + intoClause,
                "                 FROM MYDB.KEIYAKU",
                "           END-EXEC",
                "           GOBACK.",
                "");
    }

    private static final String WITHOUT_INDICATOR =
            program("FIX037", ":WS-KEIYAKU-NO, :WS-SHORI-KBN");
    private static final String WITH_INDICATOR =
            program("FIX037B", ":WS-KEIYAKU-NO, :WS-SHORI-KBN :WS-SHORI-KBN-IND");

    @Test
    void detectsNullableColumnReceivedWithoutAnIndicator() {
        CobolSemanticModel model =
                SqlAdviceFixtures.parse(tempDir, "FIX037.cbl", WITHOUT_INDICATOR);
        List<Finding> findings = new NullIndicatorMissingRule()
                .evaluate(SqlAdviceFixtures.programContext(model));
        assertEquals(1, findings.size(), () -> "標識のないホスト変数1件を検出すること: " + findings);
        assertEquals("R037", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("SHORI_KBN"), findings.get(0).message());
        assertEquals(17, findings.get(0).location().line(), "INTO の並びの行で報告すること");
    }

    @Test
    void ignoresNullableColumnReceivedWithAnIndicator() {
        CobolSemanticModel model =
                SqlAdviceFixtures.parse(tempDir, "FIX037B.cbl", WITH_INDICATOR);
        List<Finding> findings = new NullIndicatorMissingRule()
                .evaluate(SqlAdviceFixtures.programContext(model));
        assertEquals(List.of(), findings, () -> "標識ありは対象外: " + findings);
    }

    /** A table with no DECLARE TABLE in the folder says nothing about nullability. */
    @Test
    void ignoresATableWithNoDeclareTable() {
        String source = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. FIX037C.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                "           EXEC SQL DECLARE MYDB.KEIYAKU TABLE",
                "           ( KEIYAKU_NO      CHAR(10) NOT NULL,",
                "             SHORI_KBN       CHAR(1)",
                "           ) END-EXEC.",
                "       01  WS-SHORI-KBN      PIC X(01).",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           EXEC SQL",
                "               SELECT SHORI_KBN INTO :WS-SHORI-KBN",
                "                 FROM MYDB.NYUKIN",
                "           END-EXEC",
                "           GOBACK.",
                "");
        CobolSemanticModel model = SqlAdviceFixtures.parse(tempDir, "FIX037C.cbl", source);
        List<Finding> findings = new NullIndicatorMissingRule()
                .evaluate(SqlAdviceFixtures.programContext(model));
        assertEquals(List.of(), findings, () -> "DCLGEN のない表は対象外: " + findings);
    }
}
