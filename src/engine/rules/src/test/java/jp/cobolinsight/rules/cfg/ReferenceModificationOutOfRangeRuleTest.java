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

/** Synthetic-fixture verification for R042, a reference modification past the item's length. */
class ReferenceModificationOutOfRangeRuleTest {

    @TempDir
    Path tempDir;

    private static String program(String programId, String items, String... procedure) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION."));
        sb.append("\n").append(items).append("\n");
        sb.append("       PROCEDURE DIVISION.\n       MAIN-PARA.\n");
        for (String line : procedure) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private List<Finding> run(String programId, String text) {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, programId + ".cbl", text);
        return new ReferenceModificationOutOfRangeRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsReferenceModificationPastTheItemsLength() {
        String text = program("F042A",
                "       01  WS-NAME  PIC X(20).\n"
                        + "       01  WS-OUT   PIC X(05).",
                "           MOVE WS-NAME(20:5) TO WS-OUT",
                "           STOP RUN.");
        List<Finding> findings = run("F042A", text);
        assertEquals(1, findings.size(), () -> "項目長超過の部分参照を1件検出すること: " + findings);
        assertEquals("R042", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("WS-NAME"), findings.get(0).message());
    }

    @Test
    void detectsStartPastTheItemsLength() {
        String text = program("F042B",
                "       01  WS-NAME  PIC X(10).\n"
                        + "       01  WS-OUT   PIC X(05).",
                "           MOVE WS-NAME(11:5) TO WS-OUT",
                "           STOP RUN.");
        List<Finding> findings = run("F042B", text);
        assertEquals(1, findings.size(), () -> "開始位置が項目長を超える部分参照を検出すること: " + findings);
        assertEquals("R042", findings.get(0).ruleId());
    }

    @Test
    void ignoresReferenceModificationWithinRange() {
        String text = program("F042C",
                "       01  WS-NAME  PIC X(20).\n"
                        + "       01  WS-OUT   PIC X(05).",
                "           MOVE WS-NAME(16:5) TO WS-OUT",
                "           STOP RUN.");
        assertEquals(List.of(), run("F042C", text), "項目長以内の部分参照は対象外");
    }

    @Test
    void ignoresNonLiteralBounds() {
        String text = program("F042D",
                "       01  WS-NAME  PIC X(10).\n"
                        + "       01  WS-START PIC 9(02) VALUE 20.\n"
                        + "       01  WS-OUT   PIC X(05).",
                "           MOVE WS-NAME(WS-START:5) TO WS-OUT",
                "           STOP RUN.");
        assertEquals(List.of(), run("F042D", text), "start・length がデータ項目の部分参照は対象外");
    }
}
