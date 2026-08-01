package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R013 EVALUATE文のWHEN OTHER欠如の合成fixture検証。 */
class EvaluateWhenOtherRuleTest {

    @TempDir
    Path tempDir;

    private static final String SOURCE = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID.  FIX013.",
            /*  3 */ "       ENVIRONMENT DIVISION.",
            /*  4 */ "       DATA DIVISION.",
            /*  5 */ "       WORKING-STORAGE SECTION.",
            /*  6 */ "       01  WS-KBN                      PIC X(01).",
            /*  7 */ "       PROCEDURE DIVISION.",
            /*  8 */ "       0000-MAIN.",
            /*  9 */ "           EVALUATE WS-KBN",
            /* 10 */ "               WHEN '1'",
            /* 11 */ "                   CONTINUE",
            /* 12 */ "               WHEN '2'",
            /* 13 */ "                   CONTINUE",
            /* 14 */ "           END-EVALUATE",
            /* 15 */ "           EVALUATE WS-KBN",
            /* 16 */ "               WHEN '1'",
            /* 17 */ "                   CONTINUE",
            /* 18 */ "               WHEN OTHER",
            /* 19 */ "                   CONTINUE",
            /* 20 */ "           END-EVALUATE",
            /* 21 */ "           IF WS-KBN = '1'",
            /* 22 */ "               CONTINUE",
            /* 23 */ "           END-IF",
            /* 24 */ "           GOBACK.",
            "");

    @Test
    void detectsEvaluateWithoutWhenOtherAndSkipsIfStatements() {
        CobolSemanticModel model = Fixtures.parse(tempDir, "FIX013.cbl", SOURCE);

        List<Finding> findings = new EvaluateWhenOtherRule()
                .evaluate(Fixtures.context(List.of(model), Map.of(model.sourceFile(), SOURCE)));

        assertEquals(1, findings.size(), () -> "検出: " + findings);
        Finding finding = findings.get(0);
        assertEquals("R013", finding.ruleId());
        assertEquals(FindingLevel.WARNING, finding.level());
        assertEquals(9, finding.location().line());
    }
}
