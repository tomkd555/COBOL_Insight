package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R005 OCCURS 範囲外の添字・指標の合成fixture検証(無検査カウンタ・範囲内ループ・LINKAGE 表)。 */
class OccursSubscriptRangeRuleTest {

    @TempDir
    Path tempDir;

    private List<Finding> run(String programId, String text) {
        CobolSemanticModel model = DataFlowFixtures.parse(tempDir, programId + ".cbl", text);
        return new OccursSubscriptRangeRule()
                .evaluate(DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsUncheckedCounterSubscript() {
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. F005A.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-EOF   PIC X(01) VALUE 'N'.",
                "       01  WS-IDX   PIC S9(04) COMP VALUE ZERO.",
                "       01  WS-TBL.",
                "           05  WS-ELEM  PIC X(04) OCCURS 5 TIMES.",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           PERFORM 100-STORE UNTIL WS-EOF = 'Y'",
                "           STOP RUN.",
                "       100-STORE.",
                "           ADD 1 TO WS-IDX",
                "           MOVE 'AAAA' TO WS-ELEM(WS-IDX)",
                "           ACCEPT WS-EOF.",
                "");
        List<Finding> findings = run("F005A", text);
        assertEquals(1, findings.size(), () -> "上限未検査の添字を1件検出すること: " + findings);
        assertEquals("R005", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("WS-ELEM"), findings.get(0).message());
    }

    @Test
    void ignoresVaryingLoopWithinBounds() {
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. F005B.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-IDX   PIC S9(04) COMP.",
                "       01  WS-TBL.",
                "           05  WS-ELEM  PIC X(04) OCCURS 5 TIMES.",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           PERFORM VARYING WS-IDX FROM 1 BY 1",
                "                   UNTIL WS-IDX > 5",
                "               MOVE 'AAAA' TO WS-ELEM(WS-IDX)",
                "           END-PERFORM",
                "           STOP RUN.",
                "");
        assertEquals(List.of(), run("F005B", text), "上限内で歩進するループの添字は対象外");
    }

    @Test
    void ignoresLinkageTable() {
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. F005C.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-IDX   PIC S9(04) COMP VALUE ZERO.",
                "       LINKAGE SECTION.",
                "       01  LK-TBL.",
                "           05  LK-ELEM  PIC X(04) OCCURS 5 TIMES.",
                "       PROCEDURE DIVISION USING LK-TBL.",
                "       MAIN-PARA.",
                "           ADD 1 TO WS-IDX",
                "           MOVE 'AAAA' TO LK-ELEM(WS-IDX)",
                "           STOP RUN.",
                "");
        assertEquals(List.of(), run("F005C", text),
                "LINKAGE 節の表(呼出元が保証)は対象外");
    }
}
