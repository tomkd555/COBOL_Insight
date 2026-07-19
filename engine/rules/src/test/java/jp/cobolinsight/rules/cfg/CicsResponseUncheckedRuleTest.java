package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R021 CICS応答コード未検査の合成fixture検証(RESPなしで検出・RESPありで非検出)。 */
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
}
