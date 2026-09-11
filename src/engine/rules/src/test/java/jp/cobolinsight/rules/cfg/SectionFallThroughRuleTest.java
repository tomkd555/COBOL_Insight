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

/** R014 synthetic fixture verification for section fall-through. */
class SectionFallThroughRuleTest {

    @TempDir
    Path tempDir;

    private static final String FALL_THROUGH = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX014.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-D PIC 9(01).",
            "       PROCEDURE DIVISION.",
            "       MAIN-SECTION SECTION.",
            "       M-1.",
            "           PERFORM SEC-A",
            "           STOP RUN.",
            "       SEC-A SECTION.",
            "       A-1.",
            "           MOVE 1 TO WS-D.",
            "       SEC-B SECTION.",
            "       B-1.",
            "           MOVE 2 TO WS-D",
            "           EXIT.",
            "");

    private static final String TERMINATED = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX014B.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-D PIC 9(01).",
            "       PROCEDURE DIVISION.",
            "       MAIN-SECTION SECTION.",
            "       M-1.",
            "           PERFORM SEC-A",
            "           STOP RUN.",
            "       SEC-A SECTION.",
            "       A-1.",
            "           MOVE 1 TO WS-D",
            "           EXIT.",
            "       SEC-B SECTION.",
            "       B-1.",
            "           MOVE 2 TO WS-D",
            "           EXIT.",
            "");

    @Test
    void detectsSectionFallingThroughToNext() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX014.cbl", FALL_THROUGH);
        List<Finding> findings = new SectionFallThroughRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), FALL_THROUGH)));
        assertEquals(1, findings.size(), () -> "末尾EXITなしのSEC-A流下1件を検出すること: " + findings);
        assertEquals("R014", findings.get(0).ruleId());
        assertEquals(FindingLevel.NOTE, findings.get(0).level(),
                "field code showed only style hits, so R014 ships at LOW");
        assertEquals(13, findings.get(0).location().line());
    }

    @Test
    void ignoresSectionEndingWithExit() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX014B.cbl", TERMINATED);
        List<Finding> findings = new SectionFallThroughRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), TERMINATED)));
        assertEquals(List.of(), findings, () -> "末尾EXITの節は流下しない: " + findings);
    }
}
