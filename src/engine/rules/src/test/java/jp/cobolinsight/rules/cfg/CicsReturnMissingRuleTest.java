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

/** R022 CICS RETURN欠如。終端がIF内に入れ子でも検出することと、RETURNを持てば非検出を確認する。 */
class CicsReturnMissingRuleTest {

    @TempDir
    Path tempDir;

    /** EXEC CICS を持つ(CICS参加)が RETURN を持たず、終端 GOBACK が IF 内に入れ子。 */
    private static final String NESTED_TERMINAL = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID. FIX022.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "       01  WS-FLAG PIC X VALUE 'Y'.",
            /*  6 */ "           88  WS-DONE VALUE 'Y'.",
            /*  7 */ "       01  WS-MAP.",
            /*  8 */ "           05  WS-F PIC X(08).",
            /*  9 */ "       PROCEDURE DIVISION.",
            /* 10 */ "       0000-MAIN.",
            /* 11 */ "           EXEC CICS",
            /* 12 */ "               SEND MAP('MAP1')",
            /* 13 */ "                   MAPSET('SET1')",
            /* 14 */ "                   FROM(WS-MAP)",
            /* 15 */ "           END-EXEC",
            /* 16 */ "           IF WS-DONE",
            /* 17 */ "               GOBACK",
            /* 18 */ "           END-IF",
            /* 19 */ "           DISPLAY 'CONTINUE'.",
            "");

    /** EXEC CICS RETURN TRANSID を持つ(疑似会話を正しく継続)。 */
    private static final String WITH_RETURN = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX022B.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-MAP.",
            "           05  WS-F PIC X(08).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC CICS",
            "               SEND MAP('MAP1')",
            "                   MAPSET('SET1')",
            "                   FROM(WS-MAP)",
            "           END-EXEC",
            "           EXEC CICS",
            "               RETURN TRANSID('TX01')",
            "           END-EXEC.",
            "");

    @Test
    void detectsNestedTerminalWhenCicsProgramLacksReturn() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX022.cbl", NESTED_TERMINAL);
        List<Finding> findings = new CicsReturnMissingRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), NESTED_TERMINAL)));
        assertEquals(1, findings.size(), () -> "RETURN欠如を1件検出すること: " + findings);
        assertEquals("R022", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertEquals(17, findings.get(0).location().line(),
                () -> "IF内に入れ子の終端GOBACK(17行)へアンカーすること: " + findings);
    }

    @Test
    void ignoresCicsProgramWithReturnTransid() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX022B.cbl", WITH_RETURN);
        List<Finding> findings = new CicsReturnMissingRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), WITH_RETURN)));
        assertEquals(List.of(), findings, () -> "RETURN TRANSID を持てば非検出: " + findings);
    }
}
