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

/** R009 synthetic fixture verification for a GO TO that crosses sections. */
class GoToStructureDeviationRuleTest {

    @TempDir
    Path tempDir;

    private static final String INTER_SECTION = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX009.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-D PIC 9(01).",
            "       PROCEDURE DIVISION.",
            "       SEC-A SECTION.",
            "       A-1.",
            "           GO TO B-1.",
            "       SEC-B SECTION.",
            "       B-1.",
            "           MOVE 1 TO WS-D",
            "           GOBACK.",
            "");

    private static final String INTRA_SECTION = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX009B.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-D PIC 9(01).",
            "       PROCEDURE DIVISION.",
            "       SEC-A SECTION.",
            "       A-1.",
            "           GO TO A-2.",
            "       A-2.",
            "           MOVE 1 TO WS-D",
            "           GOBACK.",
            "");

    @Test
    void detectsInterSectionGoTo() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX009.cbl", INTER_SECTION);
        List<Finding> findings = new GoToStructureDeviationRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), INTER_SECTION)));
        assertEquals(1, findings.size(), () -> "節をまたぐGO TO1件を検出すること: " + findings);
        assertEquals("R009", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertEquals(9, findings.get(0).location().line());
    }

    @Test
    void ignoresIntraSectionGoTo() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX009B.cbl", INTRA_SECTION);
        List<Finding> findings = new GoToStructureDeviationRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), INTRA_SECTION)));
        assertEquals(List.of(), findings, () -> "同一節内のGO TOは対象外: " + findings);
    }

    /** A GO TO among a section's own leading statements, before any paragraph, is judged too. */
    @Test
    void aGoToAmongASectionsLeadingStatementsIsJudged() {
        String text = String.join("\n",
                /*  1 */ "       IDENTIFICATION DIVISION.",
                /*  2 */ "       PROGRAM-ID. FIX009D.",
                /*  3 */ "       PROCEDURE DIVISION.",
                /*  4 */ "       SEC-A SECTION.",
                /*  5 */ "           GO TO B-1.",
                /*  6 */ "       SEC-B SECTION.",
                /*  7 */ "       B-1.",
                /*  8 */ "           GOBACK.",
                "");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX009D.cbl", text);
        List<Finding> findings = new GoToStructureDeviationRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(List.of(5), findings.stream().map(f -> f.location().line()).toList());
    }

    /**
     * P1 is a paragraph of SEC-A and of SEC-B. The unqualified GO TO P1 of SEC-A means SEC-A's
     * own, the GO TO P1 OF SEC-B leaves the section, and the GO TO between two paragraphs of the
     * THRU range B-1 THRU B-3 stays in SEC-B.
     */
    @Test
    void aQualifierNamesTheSectionAndAThruRangeKeepsItsGoTo() {
        String text = String.join("\n",
                /*  1 */ "       IDENTIFICATION DIVISION.",
                /*  2 */ "       PROGRAM-ID. FIX009C.",
                /*  3 */ "       PROCEDURE DIVISION.",
                /*  4 */ "       SEC-A SECTION.",
                /*  5 */ "       A-1.",
                /*  6 */ "           PERFORM B-1 THRU B-3",
                /*  7 */ "           GO TO P1.",
                /*  8 */ "       P1.",
                /*  9 */ "           GO TO P1 OF SEC-B.",
                /* 10 */ "       SEC-B SECTION.",
                /* 11 */ "       B-1.",
                /* 12 */ "           GO TO B-3.",
                /* 13 */ "       B-2.",
                /* 14 */ "           DISPLAY 'B2'.",
                /* 15 */ "       B-3.",
                /* 16 */ "           EXIT.",
                /* 17 */ "       P1.",
                /* 18 */ "           GOBACK.",
                "");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX009C.cbl", text);
        List<Finding> findings = new GoToStructureDeviationRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(List.of(9), findings.stream().map(f -> f.location().line()).toList());
    }
}
