package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.finding.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Since lint scans bms/, a malformed BMS source must not crash the whole analysis; instead, its
 * parse failure is reported as a finding for that file and analysis continues.
 */
class LintBmsRobustnessTest {

    @Test
    void malformedBmsIsReportedAsFindingWithoutCrashingLint(@TempDir Path inputDir)
            throws IOException {
        Path bmsDir = Files.createDirectories(inputDir.resolve("bms"));
        Files.writeString(bmsDir.resolve("BROKEN.bms"),
                "!@#$%^&*() garbage tokens 123\n", StandardCharsets.UTF_8);

        LintRunner.Result result = LintRunner.run(new LintRunner.Options(
                inputDir, List.of(), Map.of()));

        assertTrue(result.findings().stream()
                        .anyMatch(f -> f.ruleId().equals(Finding.PARSE_FAILURE_RULE_ID)
                                && f.location().file().equals("bms/BROKEN.bms")),
                () -> "不正な BMS はパース失敗の finding になること: " + result.findings());
        assertFalse(result.sarifJson().isBlank(), "SARIF を出力し正常終了すること");
    }
}
