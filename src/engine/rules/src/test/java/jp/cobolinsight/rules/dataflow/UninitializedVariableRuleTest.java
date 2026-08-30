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

/** Synthetic fixture verification for R001 uninitialized variable references
 * (handling of conditional initialization, no explicit assignment, and USING parameters). */
class UninitializedVariableRuleTest {

    @TempDir
    Path tempDir;

    private List<Finding> run(String programId, String text) {
        CobolSemanticModel model = DataFlowFixtures.parse(tempDir, programId + ".cbl", text);
        return new UninitializedVariableRule()
                .evaluate(DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsConditionallyInitializedItemUsedInCondition() {
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. F001A.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-FLAG  PIC X(01) VALUE 'N'.",
                "       01  WS-CHK   PIC 9(03).",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           IF WS-FLAG = 'Y'",
                "               MOVE 100 TO WS-CHK",
                "           END-IF",
                "           IF WS-CHK > 50",
                "               DISPLAY 'HIGH'",
                "           END-IF",
                "           STOP RUN.",
                "");
        List<Finding> findings = run("F001A", text);
        assertEquals(1, findings.size(), () -> "条件付き初期化の未初期化参照を1件検出すること: " + findings);
        assertEquals("R001", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("WS-CHK"), findings.get(0).message());
    }

    @Test
    void groupAssignmentInitializesSubordinateItems() {
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. F001G.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-FLAG  PIC X(01) VALUE 'N'.",
                "       01  WS-SUM   PIC 9(05).",
                "       01  WS-GRP.",
                "           05  WS-A   PIC 9(04).",
                "           05  WS-B   PIC 9(04).",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           MOVE ZERO TO WS-GRP",
                "           IF WS-FLAG = 'Y'",
                "               MOVE 5 TO WS-A",
                "           END-IF",
                "           COMPUTE WS-SUM = WS-A + 1",
                "           STOP RUN.",
                "");
        assertEquals(List.of(), run("F001G", text),
                "集団項目への代入は従属項目を初期化するため未初期化としないこと");
    }

    @Test
    void ignoresItemWithValueClause() {
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. F001B.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-FLAG  PIC X(01) VALUE 'N'.",
                "       01  WS-CHK   PIC 9(03) VALUE ZERO.",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           IF WS-FLAG = 'Y'",
                "               MOVE 100 TO WS-CHK",
                "           END-IF",
                "           IF WS-CHK > 50",
                "               DISPLAY 'HIGH'",
                "           END-IF",
                "           STOP RUN.",
                "");
        assertEquals(List.of(), run("F001B", text), "VALUE 句を持つ項目は対象外");
    }

    @Test
    void ignoresItemNeverExplicitlyAssigned() {
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. F001C.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-STATUS  PIC X(02).",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           IF WS-STATUS = '00'",
                "               DISPLAY 'OK'",
                "           END-IF",
                "           STOP RUN.",
                "");
        assertEquals(List.of(), run("F001C", text),
                "明示代入を持たない項目(ファイルステータス等の相当)は対象外");
    }

    @Test
    void ignoresLinkageUsingParameter() {
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. F001D.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-WORK   PIC 9(03).",
                "       LINKAGE SECTION.",
                "       01  LK-IN     PIC 9(03).",
                "       PROCEDURE DIVISION USING LK-IN.",
                "       MAIN-PARA.",
                "           MOVE LK-IN TO WS-WORK",
                "           IF LK-IN > 50",
                "               DISPLAY 'HIGH'",
                "           END-IF",
                "           STOP RUN.",
                "");
        assertEquals(List.of(), run("F001D", text),
                "PROCEDURE DIVISION USING 引数(呼出元が初期化)は対象外");
    }
}
