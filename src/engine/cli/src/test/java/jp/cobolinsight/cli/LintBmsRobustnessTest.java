package jp.cobolinsight.cli;

import jp.cobolinsight.engineapi.finding.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * lint は bms/ を走査するため、不正な BMS ソースでも解析全体をクラッシュさせず、当該ファイルの
 * パース失敗を finding として報告して継続する。
 */
class LintBmsRobustnessTest {

    @Test
    void malformedBmsIsReportedAsFindingWithoutCrashingLint(@TempDir Path inputDir)
            throws IOException {
        Path bmsDir = Files.createDirectories(inputDir.resolve("bms"));
        Files.writeString(bmsDir.resolve("BROKEN.bms"),
                "!@#$%^&*() garbage tokens 123\n", StandardCharsets.UTF_8);

        LintRunner.Result result = LintRunner.run(new LintRunner.Options(
                inputDir, List.of(), Map.of(), Set.of()));

        assertTrue(result.findings().stream()
                        .anyMatch(f -> f.ruleId().equals(Finding.PARSE_FAILURE_RULE_ID)
                                && f.location().file().equals("bms/BROKEN.bms")),
                () -> "不正な BMS はパース失敗の finding になること: " + result.findings());
        assertFalse(result.sarifJson().isBlank(), "SARIF を出力し正常終了すること");
    }
}
