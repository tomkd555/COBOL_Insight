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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R008 PERFORM単独段落名の直接指定の合成fixture検証。 */
class PerformSingleParagraphRuleTest {

    @TempDir
    Path tempDir;

    private static final String SOURCE = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID.  FIX008.",
            /*  3 */ "       ENVIRONMENT DIVISION.",
            /*  4 */ "       DATA DIVISION.",
            /*  5 */ "       WORKING-STORAGE SECTION.",
            /*  6 */ "       01  WS-DUMMY                    PIC 9(01).",
            /*  7 */ "       PROCEDURE DIVISION.",
            /*  8 */ "       0000-MAIN SECTION.",
            /*  9 */ "       0010-START.",
            /* 10 */ "           PERFORM 1000-STEP",
            /* 11 */ "           PERFORM 2000-RANGE THRU 2000-EXIT",
            /* 12 */ "           PERFORM 3000-SUB",
            /* 13 */ "           PERFORM UNTIL WS-DUMMY > 3",
            /* 14 */ "               MOVE 9 TO WS-DUMMY",
            /* 15 */ "           END-PERFORM",
            /* 16 */ "           GOBACK.",
            /* 17 */ "       1000-STEP.",
            /* 18 */ "           MOVE 1 TO WS-DUMMY.",
            /* 19 */ "       2000-RANGE.",
            /* 20 */ "           MOVE 2 TO WS-DUMMY.",
            /* 21 */ "       2000-EXIT.",
            /* 22 */ "           EXIT.",
            /* 23 */ "       3000-SUB SECTION.",
            /* 24 */ "       3100-PARA.",
            /* 25 */ "           MOVE 3 TO WS-DUMMY.",
            "");

    private static final String AMBIGUOUS = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID.  FIX008B.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "       01  WS-DUMMY                    PIC 9(01).",
            /*  6 */ "       PROCEDURE DIVISION.",
            /*  7 */ "       0000-MAIN SECTION.",
            /*  8 */ "       0010-START.",
            /*  9 */ "           PERFORM 3000-SUB",
            /* 10 */ "           GOBACK.",
            /* 11 */ "       3000-SUB.",
            /* 12 */ "           MOVE 1 TO WS-DUMMY.",
            /* 13 */ "       3000-SUB SECTION.",
            /* 14 */ "       3100-P.",
            /* 15 */ "           MOVE 2 TO WS-DUMMY.",
            "");

    @Test
    void skipsPerformWhoseTargetNameIsBothParagraphAndSection() {
        CobolSemanticModel model = Fixtures.parse(tempDir, "FIX008B.cbl", AMBIGUOUS);

        List<Finding> findings = new PerformSingleParagraphRule()
                .evaluate(Fixtures.context(List.of(model),
                        Map.of(model.sourceFile(), AMBIGUOUS)));

        assertEquals(List.of(), findings,
                "段落とセクションの両方に存在する名前は遷移先種別が曖昧なため"
                        + "判定を保留する(誤検出よりも未検出を選ぶ)こと");
    }

    @Test
    void detectsPerformOfSingleParagraphWithoutThru() {
        CobolSemanticModel model = Fixtures.parse(tempDir, "FIX008.cbl", SOURCE);

        List<Finding> findings = new PerformSingleParagraphRule()
                .evaluate(Fixtures.context(List.of(model), Map.of(model.sourceFile(), SOURCE)));

        assertEquals(1, findings.size(),
                () -> "インラインPERFORM(UNTIL)を検出対象に含めないこと: " + findings);
        Finding finding = findings.get(0);
        assertEquals("R008", finding.ruleId());
        assertEquals(FindingLevel.WARNING, finding.level());
        assertEquals(10, finding.location().line());
        assertTrue(finding.message().contains("1000-STEP"), finding.message());
    }
}
