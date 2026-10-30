package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R012 synthetic fixture verification for a PERFORM UNTIL whose exit condition is never updated. */
class PerformUntilNotUpdatedRuleTest {

    @TempDir
    Path tempDir;

    private static String program(String programId, String... procedure) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-DONE-FLAG  PIC X(01) VALUE 'N'.",
                "       01  WS-COUNTER    PIC 9(03) VALUE ZERO.",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA."));
        sb.append("\n");
        for (String line : procedure) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private List<Finding> run(String programId, String text) {
        CobolSemanticModel model = DataFlowFixtures.parse(tempDir, programId + ".cbl", text);
        return new PerformUntilNotUpdatedRule()
                .evaluate(DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsInlineLoopWhoseConditionVariableIsNeverUpdated() {
        String text = program("F012A",
                "           PERFORM UNTIL WS-DONE-FLAG = 'Y'",
                "               ADD 1 TO WS-COUNTER",
                "               DISPLAY WS-COUNTER",
                "           END-PERFORM",
                "           STOP RUN.");
        List<Finding> findings = run("F012A", text);
        assertEquals(1, findings.size(), () -> "更新されない終了条件を1件検出すること: " + findings);
        assertEquals("R012", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("WS-DONE-FLAG"), findings.get(0).message());
    }

    @Test
    void ignoresInlineLoopWhoseConditionVariableIsUpdatedInBody() {
        String text = program("F012B",
                "           PERFORM UNTIL WS-DONE-FLAG = 'Y'",
                "               ADD 1 TO WS-COUNTER",
                "               IF WS-COUNTER > 10",
                "                   MOVE 'Y' TO WS-DONE-FLAG",
                "               END-IF",
                "           END-PERFORM",
                "           STOP RUN.");
        assertEquals(List.of(), run("F012B", text),
                "ループ本体で終了条件変数を更新する場合は対象外");
    }

    @Test
    void detectsParagraphPerformWhoseConditionVariableIsNeverUpdated() {
        String text = program("F012C",
                "           PERFORM SUB-PARA UNTIL WS-DONE-FLAG = 'Y'",
                "           STOP RUN.",
                "       SUB-PARA.",
                "           ADD 1 TO WS-COUNTER.");
        List<Finding> findings = run("F012C", text);
        assertEquals(1, findings.size(), () -> "段落 PERFORM UNTIL の未更新を検出すること: " + findings);
        assertEquals("R012", findings.get(0).ruleId());
    }

    /**
     * The flag is reset just before the inner loop, inside an outer loop. That MOVE runs between
     * iterations of the outer loop, never between two tests of the inner UNTIL, so it must not
     * count as an update of the inner loop's condition.
     */
    @Test
    void detectsInnerLoopWhoseFlagIsOnlyResetByTheOuterLoop() {
        String text = program("F012E",
                "           PERFORM UNTIL WS-COUNTER > 5",
                "               ADD 1 TO WS-COUNTER",
                "               MOVE 'N' TO WS-DONE-FLAG",
                "               PERFORM SUB-PARA UNTIL WS-DONE-FLAG = 'Y'",
                "           END-PERFORM",
                "           STOP RUN.",
                "       SUB-PARA.",
                "           DISPLAY WS-COUNTER.");
        List<Finding> findings = run("F012E", text);
        assertEquals(1, findings.size(), () -> "内側ループの未更新を検出すること: " + findings);
        assertTrue(findings.get(0).message().contains("WS-DONE-FLAG"), findings.get(0).message());
    }

    @Test
    void ignoresParagraphPerformWhoseConditionVariableIsUpdatedInCalledParagraph() {
        String text = program("F012D",
                "           PERFORM SUB-PARA UNTIL WS-DONE-FLAG = 'Y'",
                "           STOP RUN.",
                "       SUB-PARA.",
                "           ADD 1 TO WS-COUNTER",
                "           IF WS-COUNTER > 10",
                "               MOVE 'Y' TO WS-DONE-FLAG",
                "           END-IF.");
        assertEquals(List.of(), run("F012D", text),
                "呼び出す段落で終了条件変数を更新する場合は対象外");
    }

    /** UNTIL at the end of a line, its condition on the next, is the UNTIL of the PERFORM. */
    @Test
    void untilAtTheEndOfALineIsFound() {
        String text = program("F012H",
                "           PERFORM SUB-PARA UNTIL",
                "               WS-DONE-FLAG = 'Y'",
                "           STOP RUN.",
                "       SUB-PARA.",
                "           ADD 1 TO WS-COUNTER.");
        List<Finding> findings = run("F012H", text);
        assertEquals(List.of(9), findings.stream().map(f -> f.location().line()).toList());
        assertTrue(findings.get(0).message().contains("WS-DONE-FLAG"), findings.get(0).message());
    }

    /**
     * Two files declare PROGRAM-ID F012G. Each is analysed on its own graph and its own data flow:
     * the first updates its flag, the second does not and is reported in its own file.
     */
    @Test
    void twoFilesOfOneProgramIdEachHaveTheirOwnDataFlow() throws IOException {
        String updates = program("F012G",
                "           PERFORM UNTIL WS-DONE-FLAG = 'Y'",
                "               MOVE 'Y' TO WS-DONE-FLAG",
                "           END-PERFORM",
                "           STOP RUN.");
        String stuck = program("F012G",
                "           DISPLAY 'START'",
                "           PERFORM UNTIL WS-DONE-FLAG = 'Y'",
                "               ADD 1 TO WS-COUNTER",
                "           END-PERFORM",
                "           STOP RUN.");
        CobolSemanticModel first = DataFlowFixtures.parse(
                Files.createDirectories(tempDir.resolve("first")), "F012G.cbl", updates);
        CobolSemanticModel second = DataFlowFixtures.parse(
                Files.createDirectories(tempDir.resolve("second")), "F012G.cbl", stuck);

        List<Finding> findings = new PerformUntilNotUpdatedRule().evaluate(DataFlowFixtures.context(
                List.of(first, second), Map.of(first.sourceFile(), updates,
                        second.sourceFile(), stuck)));

        assertEquals(List.of(second.sourceFile() + ":10"), findings.stream()
                .map(f -> f.location().file() + ":" + f.location().line()).toList());
    }

    /**
     * The first loop runs R-1 IN LOOP-SECT THRU R-3, three paragraphs, and the third updates its
     * flag. The second loop is copied in and nothing updates its flag; the finding stands in the
     * copybook at the copybook's line.
     */
    @Test
    void aThruRangeUpdatesInItsLastParagraphAndACopiedLoopIsReportedInItsCopybook()
            throws IOException {
        Path copybooks = Files.createDirectories(tempDir.resolve("cpy"));
        Files.writeString(copybooks.resolve("STUCK.cpy"),
                "           PERFORM S-1 UNTIL WS-STUCK-FLAG = 'Y'\n");
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. F012F.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-DONE-FLAG  PIC X(01) VALUE 'N'.",
                "       01  WS-STUCK-FLAG PIC X(01) VALUE 'N'.",
                "       PROCEDURE DIVISION.",
                "       MAIN-SECT SECTION.",
                "       M-START.",
                "           PERFORM R-1 IN LOOP-SECT THRU R-3 UNTIL WS-DONE-FLAG = 'Y'",
                "           COPY STUCK.",
                "           STOP RUN.",
                "       LOOP-SECT SECTION.",
                "       R-1.",
                "           DISPLAY 'R1'.",
                "       R-2.",
                "           DISPLAY 'R2'.",
                "       R-3.",
                "           MOVE 'Y' TO WS-DONE-FLAG.",
                "       S-1.",
                "           DISPLAY 'S1'.",
                "");
        CobolSemanticModel model =
                DataFlowFixtures.parse(tempDir, "F012F.cbl", text, copybooks);
        List<Finding> findings = new PerformUntilNotUpdatedRule().evaluate(
                DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));

        assertEquals(List.of("STUCK.cpy:1"), findings.stream()
                .map(f -> Path.of(f.location().file()).getFileName() + ":" + f.location().line())
                .toList());
        assertTrue(findings.get(0).message().contains("WS-STUCK-FLAG"), findings.get(0).message());
    }
}
