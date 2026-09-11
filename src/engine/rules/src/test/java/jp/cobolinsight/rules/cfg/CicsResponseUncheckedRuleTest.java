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

/** R021 synthetic fixture verification for unchecked CICS response codes: no RESP, RESP never tested, NOHANDLE, HANDLE CONDITION. */
class CicsResponseUncheckedRuleTest {

    @TempDir
    Path tempDir;

    private static final String WITHOUT_RESP = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX021.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-MAP.",
            "           05  WS-F PIC X(08).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC CICS",
            "               RECEIVE MAP('MAP1')",
            "                   MAPSET('SET1')",
            "                   INTO(WS-MAP)",
            "           END-EXEC.",
            "");

    private static final String WITH_RESP = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX021B.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-MAP.",
            "           05  WS-F PIC X(08).",
            "       01  WS-RESP PIC S9(08) COMP.",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC CICS",
            "               RECEIVE MAP('MAP1')",
            "                   MAPSET('SET1')",
            "                   INTO(WS-MAP)",
            "                   RESP(WS-RESP)",
            "           END-EXEC",
            "           IF WS-RESP NOT = 0",
            "               DISPLAY 'CICS ERROR'",
            "           END-IF.",
            "");

    @Test
    void detectsCicsCommandWithoutResp() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX021.cbl", WITHOUT_RESP);
        List<Finding> findings = new CicsResponseUncheckedRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), WITHOUT_RESP)));
        assertEquals(1, findings.size(), () -> "RESP/RESP2なしのCICS1件を検出すること: " + findings);
        assertEquals("R021", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(13, findings.get(0).location().line());
    }

    @Test
    void ignoresCicsCommandWithResp() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX021B.cbl", WITH_RESP);
        List<Finding> findings = new CicsResponseUncheckedRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), WITH_RESP)));
        assertEquals(List.of(), findings, () -> "RESPを持つCICSは対象外: " + findings);
    }

    private static String program(String programId, String... procedureLines) {
        return String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-REC PIC X(100).",
                "       01  WS-KEY PIC X(10).",
                "       01  WS-RESP PIC S9(08) COMP.",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                String.join("\n", procedureLines),
                "           GOBACK.",
                "");
    }

    private static List<Finding> evaluate(Path dir, String programId, String text) {
        CobolSemanticModel model = CfgFixtures.parse(dir, programId + ".cbl", text);
        return new CicsResponseUncheckedRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void reportsRespThatIsReceivedButNeverTested() {
        String text = program("FIX021C",
                "           EXEC CICS WRITEQ TS QUEUE('Q1') FROM(WS-REC) LENGTH(100)",
                "                RESP(WS-RESP) END-EXEC",
                "           MOVE SPACES TO WS-REC");
        List<Finding> findings = evaluate(tempDir, "FIX021C", text);
        assertEquals(1, findings.size(), () -> "a RESP nothing tests is reported: " + findings);
        assertEquals(11, findings.get(0).location().line());
        assertTrue(findings.get(0).message().contains("WRITEQ TS Q1"), findings.get(0).message());
    }

    @Test
    void acceptsRespTestedByEvaluateBeforeTheNextCommand() {
        String text = program("FIX021D",
                "           EXEC CICS READ DATASET('F1') INTO(WS-REC) RIDFLD(WS-KEY)",
                "                RESP(WS-RESP) END-EXEC",
                "           EVALUATE WS-RESP",
                "               WHEN DFHRESP(NORMAL) CONTINUE",
                "               WHEN OTHER MOVE SPACES TO WS-REC",
                "           END-EVALUATE",
                "           EXEC CICS WRITEQ TS QUEUE('Q1') FROM(WS-REC) LENGTH(100)",
                "                RESP(WS-RESP) END-EXEC",
                "           IF WS-RESP NOT = DFHRESP(NORMAL) MOVE SPACES TO WS-REC END-IF");
        assertEquals(List.of(), evaluate(tempDir, "FIX021D", text));
    }

    @Test
    void ignoresNoHandleAndCommandsThatRaiseNoCondition() {
        String text = program("FIX021E",
                "           EXEC CICS HANDLE AID PF3(0000-MAIN) END-EXEC",
                "           EXEC CICS ASSIGN USERID(WS-KEY) END-EXEC",
                "           EXEC CICS SEND TEXT FROM(WS-REC) LENGTH(20) NOHANDLE END-EXEC",
                "           EXEC CICS RETURN END-EXEC");
        assertEquals(List.of(), evaluate(tempDir, "FIX021E", text));
    }

    @Test
    void treatsCommandsAfterHandleConditionAsHandled() {
        String text = program("FIX021F",
                "           EXEC CICS WRITEQ TS QUEUE('Q1') FROM(WS-REC) LENGTH(100)",
                "           END-EXEC",
                "           EXEC CICS HANDLE CONDITION MAPFAIL(0000-MAIN) END-EXEC",
                "           EXEC CICS RECEIVE MAP('MAP1') MAPSET('SET1') INTO(WS-REC)",
                "           END-EXEC");
        List<Finding> findings = evaluate(tempDir, "FIX021F", text);
        assertEquals(List.of(11), findings.stream().map(f -> f.location().line()).toList(),
                () -> "only the command before the HANDLE CONDITION is reported: " + findings);
    }

    @Test
    void handleConditionCoversOnlyTheConditionsItNames() {
        String text = program("FIX021G",
                "           EXEC CICS HANDLE CONDITION MAPFAIL(0000-MAIN) END-EXEC",
                "           EXEC CICS WRITEQ TS QUEUE('Q1') FROM(WS-REC) LENGTH(100)",
                "           END-EXEC",
                "           EXEC CICS RECEIVE MAP('MAP1') MAPSET('SET1') INTO(WS-REC)",
                "           END-EXEC");
        List<Finding> findings = evaluate(tempDir, "FIX021G", text);
        assertEquals(List.of(12), findings.stream().map(f -> f.location().line()).toList(),
                () -> "MAPFAIL says nothing about a WRITEQ and covers the RECEIVE MAP: " + findings);
    }

    @Test
    void handleConditionErrorCoversEveryCommand() {
        String text = program("FIX021H",
                "           EXEC CICS HANDLE CONDITION ERROR(0000-MAIN) END-EXEC",
                "           EXEC CICS WRITEQ TS QUEUE('Q1') FROM(WS-REC) LENGTH(100)",
                "           END-EXEC");
        assertEquals(List.of(), evaluate(tempDir, "FIX021H", text));
    }
}
