package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.finding.CodeFlow;
import jp.cobolinsight.core.finding.CodeFlowStep;
import jp.cobolinsight.core.finding.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * lint の SARIF が、汚染追跡由来の検出(R020・R027)へ codeFlows を付すことの検証。
 * 経路の各歩の位置も finding の位置と同じく入力フォルダ相対パスへ揃うことを併せて確認する。
 */
class LintSarifCodeFlowsTest {

    @TempDir
    Path tempDir;

    /**
     * 汚染追跡の検体。ACCEPT で受けた外部入力を STRING で連結して EXECUTE IMMEDIATE へ渡す経路
     * (R020)と、末尾が -CARD-NO の機密項目をマスキングせず DISPLAY へ渡す経路(R027)を持つ。
     */
    private static final String TAINTED = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  TAINT1.",
            "       ENVIRONMENT DIVISION.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-COND      PIC X(20).",
            "       01  WS-DYN-SQL   PIC X(100).",
            "       01  WS-CARD-NO   PIC X(16).",
            "       01  WS-WORK      PIC X(16).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           ACCEPT WS-COND",
            "           STRING 'SELECT * FROM T WHERE C = '",
            "                  DELIMITED BY SIZE",
            "                  WS-COND",
            "                  DELIMITED BY SIZE",
            "                  INTO WS-DYN-SQL",
            "           END-STRING",
            "           EXEC SQL",
            "               EXECUTE IMMEDIATE :WS-DYN-SQL",
            "           END-EXEC",
            "           MOVE WS-CARD-NO TO WS-WORK",
            "           DISPLAY WS-WORK",
            "           GOBACK.",
            "");

    private LintRunner.Result lint() throws IOException {
        Path dir = tempDir.resolve("taint");
        Files.createDirectories(dir.resolve("cobol"));
        Files.writeString(dir.resolve("cobol").resolve("TAINT1.cbl"), TAINTED,
                StandardCharsets.UTF_8);
        return LintRunner.run(new LintRunner.Options(dir, List.of(), Map.of(), Set.of()));
    }

    @Test
    void taintRuleFindingsCarryCodeFlows() throws IOException {
        Set<String> ruleIds = lint().findings().stream()
                .filter(f -> !f.codeFlows().isEmpty())
                .map(Finding::ruleId)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(Set.of("R020", "R027"), ruleIds,
                "汚染追跡段の R020・R027 の検出が経路を持つこと");
    }

    @Test
    void codeFlowStepsAreRelativizedLikeTheFindingLocation() throws IOException {
        for (Finding finding : lint().findings()) {
            for (CodeFlow codeFlow : finding.codeFlows()) {
                for (CodeFlowStep step : codeFlow.steps()) {
                    String file = step.position().file();
                    assertFalse(Path.of(file).isAbsolute(),
                            () -> "経路の各歩も入力フォルダ相対であること: " + file);
                    assertEquals(finding.location().file(), file,
                            "同一プログラム内の経路は finding と同じ相対パスを指すこと");
                }
            }
        }
    }

    @Test
    void sarifCarriesThreadFlowsForEveryCodeFlow() throws IOException {
        LintRunner.Result result = lint();
        long flowCount = result.findings().stream().mapToLong(f -> f.codeFlows().size()).sum();
        String sarif = result.sarifJson();

        assertEquals(flowCount, sarif.split("\"threadFlows\":", -1).length - 1,
                "経路の本数だけ threadFlows が出ること");
        assertTrue(sarif.contains("\"codeFlows\":[{\"threadFlows\":[{\"locations\":["),
                () -> "codeFlows → threadFlows → locations の入れ子で出ること: " + sarif);
    }
}
