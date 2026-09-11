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

/** R036 synthetic fixture verification for a COMMAREA shorter than the target's DFHCOMMAREA. */
class CommareaLengthMismatchRuleTest {

    @TempDir
    Path tempDir;

    private static final String TARGET = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX036T.",
            "       DATA DIVISION.",
            "       LINKAGE SECTION.",
            "       01  DFHCOMMAREA PIC X(10).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           GOBACK.",
            "");

    /** XCTLs to FIX036T with a 5-byte COMMAREA, shorter than the target's 10-byte DFHCOMMAREA. */
    private static final String SHORT_CALLER = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID. FIX036.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "       01  WS-AREA PIC X(05).",
            /*  6 */ "       PROCEDURE DIVISION.",
            /*  7 */ "       0000-MAIN.",
            /*  8 */ "           EXEC CICS XCTL PROGRAM('FIX036T')",
            /*  9 */ "                COMMAREA(WS-AREA) LENGTH(5)",
            /* 10 */ "           END-EXEC.",
            "");

    /** Same transfer, but LENGTH matches the target's DFHCOMMAREA exactly. */
    private static final String EXACT_CALLER = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX036B.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-AREA PIC X(10).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC CICS XCTL PROGRAM('FIX036T')",
            "                COMMAREA(WS-AREA) LENGTH(10)",
            "           END-EXEC.",
            "");

    private List<Finding> run(CobolSemanticModel caller, String callerText,
            CobolSemanticModel target, String targetText) {
        return new CommareaLengthMismatchRule().evaluate(CfgFixtures.context(
                List.of(caller, target),
                Map.of(caller.sourceFile(), callerText, target.sourceFile(), targetText)));
    }

    @Test
    void detectsCommareaShorterThanTheTargetsDfhcommarea() {
        CobolSemanticModel target = CfgFixtures.parse(tempDir, "FIX036T.cbl", TARGET);
        CobolSemanticModel caller = CfgFixtures.parse(tempDir, "FIX036.cbl", SHORT_CALLER);
        List<Finding> findings = run(caller, SHORT_CALLER, target, TARGET);
        assertEquals(1, findings.size(), () -> "長さ不一致1件を検出すること: " + findings);
        assertEquals("R036", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(9, findings.get(0).location().line(), "COMMAREA オペランドの行で報告すること");
        assertTrue(findings.get(0).message().contains("FIX036T"), findings.get(0).message());
    }

    @Test
    void ignoresCommareaThatMatchesTheTargetsLength() {
        CobolSemanticModel target = CfgFixtures.parse(tempDir, "FIX036T.cbl", TARGET);
        CobolSemanticModel caller = CfgFixtures.parse(tempDir, "FIX036B.cbl", EXACT_CALLER);
        assertEquals(List.of(), run(caller, EXACT_CALLER, target, TARGET),
                "渡す長さが受け取り側と同じなら対象外");
    }
}
