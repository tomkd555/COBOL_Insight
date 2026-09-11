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

/** R044 synthetic fixture verification for a RECEIVE MAP whose RESP is never compared with MAPFAIL. */
class MapfailUncheckedRuleTest {

    @TempDir
    Path tempDir;

    /** RESP is tested only against DFHRESP(NORMAL); MAPFAIL is never named. */
    private static final String NORMAL_ONLY = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID. FIX044.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "       01  WS-RESP PIC S9(8) COMP.",
            /*  6 */ "       01  FLM01I PIC X(80).",
            /*  7 */ "       PROCEDURE DIVISION.",
            /*  8 */ "       0000-MAIN.",
            /*  9 */ "           EXEC CICS RECEIVE MAP('FLM01') MAPSET('FLM010')",
            /* 10 */ "                INTO(FLM01I) RESP(WS-RESP)",
            /* 11 */ "           END-EXEC",
            /* 12 */ "           IF WS-RESP NOT = DFHRESP(NORMAL)",
            /* 13 */ "               PERFORM 9000-ERROR",
            /* 14 */ "           END-IF",
            /* 15 */ "           GOBACK.",
            /* 16 */ "       9000-ERROR.",
            /* 17 */ "           DISPLAY 'ERROR'.",
            "");

    /** RESP is compared with DFHRESP(MAPFAIL) in an EVALUATE branch. */
    private static final String MAPFAIL_TESTED = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX044B.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-RESP PIC S9(8) COMP.",
            "       01  FLM01I PIC X(80).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC CICS RECEIVE MAP('FLM01') MAPSET('FLM010')",
            "                INTO(FLM01I) RESP(WS-RESP)",
            "           END-EXEC",
            "           EVALUATE WS-RESP",
            "               WHEN DFHRESP(NORMAL)",
            "                   PERFORM 2000-SHORI",
            "               WHEN DFHRESP(MAPFAIL)",
            "                   PERFORM 8100-MAPFAIL",
            "               WHEN OTHER",
            "                   PERFORM 9000-ERROR",
            "           END-EVALUATE",
            "           GOBACK.",
            "       2000-SHORI.",
            "           DISPLAY 'OK'.",
            "       8100-MAPFAIL.",
            "           DISPLAY 'MAPFAIL'.",
            "       9000-ERROR.",
            "           DISPLAY 'ERROR'.",
            "");

    /** RESP is tested against DFHRESP(NORMAL) only, but the ELSE keeps every other response out of the processing. */
    private static final String NORMAL_WITH_ELSE = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX044C.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-RESP PIC S9(8) COMP.",
            "       01  FLM01I PIC X(80).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC CICS RECEIVE MAP('FLM01') MAPSET('FLM010')",
            "                INTO(FLM01I) RESP(WS-RESP)",
            "           END-EXEC",
            "           IF WS-RESP NOT = DFHRESP(NORMAL)",
            "               PERFORM 9000-ERROR",
            "           ELSE",
            "               PERFORM 2000-SHORI",
            "           END-IF",
            "           GOBACK.",
            "       2000-SHORI.",
            "           DISPLAY 'OK'.",
            "       9000-ERROR.",
            "           DISPLAY 'ERROR'.",
            "");

    private List<Finding> run(String programId, String text) {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, programId + ".cbl", text);
        return new MapfailUncheckedRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsReceiveMapThatOnlyChecksNormal() {
        List<Finding> findings = run("FIX044", NORMAL_ONLY);
        assertEquals(1, findings.size(), () -> "MAPFAIL 未検査1件を検出すること: " + findings);
        assertEquals("R044", findings.get(0).ruleId());
        assertEquals(FindingLevel.NOTE, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("FLM01"), findings.get(0).message());
    }

    @Test
    void ignoresReceiveMapThatChecksMapfail() {
        assertEquals(List.of(), run("FIX044B", MAPFAIL_TESTED), "MAPFAIL を検査していれば対象外");
    }

    @Test
    void ignoresNormalTestWhoseElseKeepsTheProcessingOut() {
        assertEquals(List.of(), run("FIX044C", NORMAL_WITH_ELSE),
                "NORMAL の否定検査に ELSE が付けば MAPFAIL は異常として扱われる");
    }
}
