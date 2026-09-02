package jp.cobolinsight.rules.syntax;

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

/** Synthetic fixture verification for R023 duplicate paragraph/section names. */
class DuplicateProcedureNameRuleTest {

    @TempDir
    Path tempDir;

    private static final String SOURCE = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID.  FIX023.",
            /*  3 */ "       ENVIRONMENT DIVISION.",
            /*  4 */ "       DATA DIVISION.",
            /*  5 */ "       WORKING-STORAGE SECTION.",
            /*  6 */ "       01  WS-DUMMY                    PIC 9(01).",
            /*  7 */ "       PROCEDURE DIVISION.",
            /*  8 */ "       0000-MAIN.",
            /*  9 */ "           GOBACK.",
            /* 10 */ "       1000-STEP.",
            /* 11 */ "           MOVE 1 TO WS-DUMMY.",
            /* 12 */ "       1000-STEP.",
            /* 13 */ "           MOVE 2 TO WS-DUMMY.",
            "");

    private static final String SECTIONED = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID.  FIX023B.",
            /*  3 */ "       ENVIRONMENT DIVISION.",
            /*  4 */ "       DATA DIVISION.",
            /*  5 */ "       WORKING-STORAGE SECTION.",
            /*  6 */ "       01  WS-DUMMY                    PIC 9(01).",
            /*  7 */ "       PROCEDURE DIVISION.",
            /*  8 */ "       1000-FIRST SECTION.",
            /*  9 */ "       100-INIT.",
            /* 10 */ "           MOVE 1 TO WS-DUMMY.",
            /* 11 */ "       2000-SECOND SECTION.",
            /* 12 */ "       100-INIT.",
            /* 13 */ "           MOVE 2 TO WS-DUMMY.",
            /* 14 */ "       3000-THIRD SECTION.",
            /* 15 */ "       200-STEP.",
            /* 16 */ "           MOVE 3 TO WS-DUMMY.",
            /* 17 */ "       200-STEP.",
            /* 18 */ "           MOVE 4 TO WS-DUMMY.",
            "");

    @Test
    void allowsSameParagraphNameInDifferentSections() {
        CobolSemanticModel model = Fixtures.parse(tempDir, "FIX023B.cbl", SECTIONED);

        List<Finding> findings = new DuplicateProcedureNameRule()
                .evaluate(Fixtures.context(List.of(model), Map.of(model.sourceFile(), SECTIONED)));

        assertTrue(findings.stream().noneMatch(f -> f.message().contains("100-INIT")),
                "異なるセクションの同名段落は合法なので検出しないこと: " + findings);
        assertEquals(1, findings.size(), () -> "検出: " + findings);
        Finding finding = findings.get(0);
        assertEquals(17, finding.location().line());
        assertTrue(finding.message().contains("200-STEP"), finding.message());
        assertTrue(finding.message().contains("15行"), finding.message());
    }

    @Test
    void detectsDuplicateParagraphNameAtSecondDeclaration() {
        CobolSemanticModel model = Fixtures.parse(tempDir, "FIX023.cbl", SOURCE);

        List<Finding> findings = new DuplicateProcedureNameRule()
                .evaluate(Fixtures.context(List.of(model), Map.of(model.sourceFile(), SOURCE)));

        assertEquals(1, findings.size(), () -> "検出: " + findings);
        Finding finding = findings.get(0);
        assertEquals("R023", finding.ruleId());
        assertEquals(FindingLevel.WARNING, finding.level());
        assertEquals(12, finding.location().line());
        assertTrue(finding.message().contains("1000-STEP"), finding.message());
        assertTrue(finding.message().contains("10行"), finding.message());
    }
}
