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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 利用者定義ルールが lint の実行経路へ載ることの検証。 */
class LintUserRulesTest {

    private static final String PROGRAM = """
           IDENTIFICATION DIVISION.
           PROGRAM-ID. USERRULE.
           PROCEDURE DIVISION.
           MAIN-PARA.
               ACCEPT WS-INPUT FROM CONSOLE.
               GOBACK.
            """;

    private static final String USER_RULES = """
            {
              "version": 1,
              "rules": [
                {
                  "id": "U001",
                  "name": "コンソール入力の使用",
                  "category": "社内規約",
                  "severity": "HIGH",
                  "targets": ["COBOL"],
                  "pattern": "FROM\\\\s+CONSOLE",
                  "message": "コンソール入力は運用規約で禁止されている"
                }
              ]
            }
            """;

    private static Path prepare(Path dir) throws IOException {
        Path cobol = dir.resolve("cobol");
        Files.createDirectories(cobol);
        Files.writeString(cobol.resolve("USERRULE.cbl"), PROGRAM, StandardCharsets.UTF_8);
        Path rules = dir.resolve("user-rules.json");
        Files.writeString(rules, USER_RULES, StandardCharsets.UTF_8);
        return rules;
    }

    private static List<Finding> userFindings(LintRunner.Result result) {
        return result.findings().stream().filter(f -> f.ruleId().startsWith("U")).toList();
    }

    @Test
    void userRuleProducesFinding(@TempDir Path dir) throws IOException {
        Path rules = prepare(dir);
        LintRunner.Result result = LintRunner.run(new LintRunner.Options(
                dir, List.of(), Map.of(), Set.of(), rules));
        List<Finding> findings = userFindings(result);
        assertEquals(1, findings.size());
        assertEquals("U001", findings.get(0).ruleId());
        assertEquals("コンソール入力は運用規約で禁止されている", findings.get(0).message());
        assertEquals("cobol/USERRULE.cbl", findings.get(0).location().file().replace('\\', '/'));
    }

    @Test
    void userRuleIsAbsentWithoutDefinitionFile(@TempDir Path dir) throws IOException {
        prepare(dir);
        LintRunner.Result result = LintRunner.run(new LintRunner.Options(
                dir, List.of(), Map.of(), Set.of()));
        assertEquals(List.of(), userFindings(result));
    }

    @Test
    void disableRuleSuppressesUserRule(@TempDir Path dir) throws IOException {
        Path rules = prepare(dir);
        LintRunner.Result result = LintRunner.run(new LintRunner.Options(
                dir, List.of(), Map.of(), Set.of("U001"), rules));
        assertEquals(List.of(), userFindings(result));
    }

    /** 利用者定義ルールの説明も SARIF の rules へ載り、読む側が指摘の意味を追える。 */
    @Test
    void userRuleAppearsInSarifRules(@TempDir Path dir) throws IOException {
        Path rules = prepare(dir);
        LintRunner.Result result = LintRunner.run(new LintRunner.Options(
                dir, List.of(), Map.of(), Set.of(), rules));
        assertTrue(result.sarifJson().contains("\"id\":\"U001\""), result.sarifJson());
        assertTrue(result.sarifJson().contains("コンソール入力の使用"), result.sarifJson());
    }
}
