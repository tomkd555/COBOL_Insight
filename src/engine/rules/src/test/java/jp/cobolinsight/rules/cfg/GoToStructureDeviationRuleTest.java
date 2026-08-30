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
}
