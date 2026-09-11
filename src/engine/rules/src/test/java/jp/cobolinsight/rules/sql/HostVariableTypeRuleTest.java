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

/** R038 synthetic fixtures: host variables that do and do not carry the DCLGEN column's type. */
class HostVariableTypeRuleTest {

    @TempDir
    Path tempDir;

    private static String program(String name, String keiyakuNoPicture, String statement) {
        return String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + name + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                "           EXEC SQL DECLARE MYDB.KEIYAKU TABLE",
                "           ( KEIYAKU_NO      CHAR(10) NOT NULL,",
                "             ZANDAKA         DECIMAL(11, 0) NOT NULL,",
                "             SHORI_KBN       CHAR(1)",
                "           ) END-EXEC.",
                "       01  WS-KEIYAKU-NO     PIC " + keiyakuNoPicture + ".",
                "       01  WS-ZANDAKA        PIC S9(11)V USAGE COMP-3.",
                "       01  WS-SHORI-KBN      PIC X(01).",
                "       01  WS-SHORI-KBN-IND  PIC X(02).",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           EXEC SQL",
                statement,
                "           END-EXEC",
                "           GOBACK.",
                "");
    }

    private static final String SELECT_INTO =
            "               SELECT KEIYAKU_NO, ZANDAKA\n"
                    + "                 INTO :WS-KEIYAKU-NO, :WS-ZANDAKA\n"
                    + "                 FROM MYDB.KEIYAKU";

    @Test
    void detectsAHostVariableShorterThanItsCharColumn() {
        CobolSemanticModel model = SqlAdviceFixtures.parse(tempDir, "FIX038.cbl",
                program("FIX038", "X(08)", SELECT_INTO));
        List<Finding> findings = new HostVariableTypeRule()
                .evaluate(SqlAdviceFixtures.programContext(model));
        assertEquals(1, findings.size(), () -> "型の合わないホスト変数1件を検出すること: " + findings);
        assertEquals("R038", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("X(10)"), findings.get(0).message());
    }

    /** DECIMAL(11,0) against S9(11)V COMP-3, and CHAR(10) against X(10): the DCLGEN mapping itself. */
    @Test
    void ignoresHostVariablesThatCarryTheColumnType() {
        CobolSemanticModel model = SqlAdviceFixtures.parse(tempDir, "FIX038B.cbl",
                program("FIX038B", "X(10)", SELECT_INTO));
        List<Finding> findings = new HostVariableTypeRule()
                .evaluate(SqlAdviceFixtures.programContext(model));
        assertEquals(List.of(), findings, () -> "DCLGEN どおりの対応は対象外: " + findings);
    }

    @Test
    void detectsAnUpdateThatSetsAColumnFromAShortHostVariable() {
        CobolSemanticModel model = SqlAdviceFixtures.parse(tempDir, "FIX038C.cbl",
                program("FIX038C", "X(08)",
                        "               UPDATE MYDB.KEIYAKU\n"
                                + "                  SET KEIYAKU_NO = :WS-KEIYAKU-NO\n"
                                + "                WHERE SHORI_KBN = :WS-SHORI-KBN"));
        List<Finding> findings = new HostVariableTypeRule()
                .evaluate(SqlAdviceFixtures.programContext(model));
        assertEquals(1, findings.size(), () -> "SET の受け渡し1件を検出すること: " + findings);
        assertTrue(findings.get(0).message().contains("KEIYAKU_NO"), findings.get(0).message());
    }

    @Test
    void detectsAnIndicatorThatIsNotABinaryHalfword() {
        CobolSemanticModel model = SqlAdviceFixtures.parse(tempDir, "FIX038D.cbl",
                program("FIX038D", "X(10)",
                        "               SELECT SHORI_KBN\n"
                                + "                 INTO :WS-SHORI-KBN :WS-SHORI-KBN-IND\n"
                                + "                 FROM MYDB.KEIYAKU"));
        List<Finding> findings = new HostVariableTypeRule()
                .evaluate(SqlAdviceFixtures.programContext(model));
        assertEquals(1, findings.size(), () -> "標識の型1件を検出すること: " + findings);
        assertTrue(findings.get(0).message().contains("WS-SHORI-KBN-IND"),
                findings.get(0).message());
    }
}
