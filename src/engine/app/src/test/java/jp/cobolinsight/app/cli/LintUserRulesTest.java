package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.rules.RuleSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

    private static final String CUSTOM_RULE = """
                {
                  "id": "U001",
                  "name": "コンソール入力の使用",
                  "category": "社内規約",
                  "summary": "ACCEPT FROM CONSOLE を使っている。",
                  "severity": "HIGH",
                  "commands": ["LINT"],
                  "targets": ["COBOL"],
                  "match": { "kind": "line", "regex": "FROM\\\\s+CONSOLE" },
                  "message": "コンソール入力は運用規約で禁止されている"
                }
            """;

    private static final String RULES_FILE = """
            {
              "version": 2,
              "custom": [
            %s
              ]
            }
            """.formatted(CUSTOM_RULE);

    private static final String RULES_FILE_DISABLED = """
            {
              "version": 2,
              "rules": { "U001": { "enabled": false } },
              "custom": [
            %s
              ]
            }
            """.formatted(CUSTOM_RULE);

    private static Path prepare(Path dir, String rulesJson) throws IOException {
        Path cobol = dir.resolve("cobol");
        Files.createDirectories(cobol);
        Files.writeString(cobol.resolve("USERRULE.cbl"), PROGRAM, StandardCharsets.UTF_8);
        Path rules = dir.resolve("rules.json");
        Files.writeString(rules, rulesJson, StandardCharsets.UTF_8);
        return rules;
    }

    private static List<Finding> userFindings(LintRunner.Result result) {
        return result.findings().stream().filter(f -> f.ruleId().startsWith("U")).toList();
    }

    private static LintRunner.Result lint(Path dir, Path rules) {
        return LintRunner.run(new LintRunner.Options(
                dir, List.of(), Map.of(), RuleSet.load(rules)));
    }

    @Test
    void userRuleProducesFinding(@TempDir Path dir) throws IOException {
        Path rules = prepare(dir, RULES_FILE);
        List<Finding> findings = userFindings(lint(dir, rules));
        assertEquals(1, findings.size());
        assertEquals("U001", findings.get(0).ruleId());
        assertEquals("コンソール入力は運用規約で禁止されている", findings.get(0).message());
        assertEquals("cobol/USERRULE.cbl", findings.get(0).location().file().replace('\\', '/'));
    }

    @Test
    void userRuleIsAbsentWithoutDefinitionFile(@TempDir Path dir) throws IOException {
        prepare(dir, RULES_FILE);
        LintRunner.Result result = LintRunner.run(new LintRunner.Options(
                dir, List.of(), Map.of()));
        assertEquals(List.of(), userFindings(result));
    }

    @Test
    void disableRuleSuppressesUserRule(@TempDir Path dir) throws IOException {
        Path rules = prepare(dir, RULES_FILE_DISABLED);
        assertEquals(List.of(), userFindings(lint(dir, rules)));
    }

    /**
     * statement 種別も targets に従う。同じ定義を COBOL 向けと COPYBOOK 向けで走らせ、
     * COBOL 本体の ACCEPT が前者だけで出ることを見る。
     */
    @Test
    void statementRuleObeysTargets(@TempDir Path dir) throws IOException {
        Path cobol = dir.resolve("cobol");
        Files.createDirectories(cobol);
        Files.writeString(cobol.resolve("TARGETS.cbl"), String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. TARGETS.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-INPUT PIC X(10).",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           ACCEPT WS-INPUT FROM CONSOLE",
                "           DISPLAY WS-INPUT",
                "           GOBACK.",
                ""), StandardCharsets.UTF_8);
        Path rules = dir.resolve("rules.json");

        Files.writeString(rules, statementRuleFile("COBOL"), StandardCharsets.UTF_8);
        List<Finding> onCobol = userFindings(lint(dir, rules));
        assertEquals(1, onCobol.size(), () -> "COBOL を対象にすれば出ること: " + onCobol);
        assertEquals("U002", onCobol.get(0).ruleId());

        Files.writeString(rules, statementRuleFile("COPYBOOK"), StandardCharsets.UTF_8);
        assertEquals(List.of(), userFindings(lint(dir, rules)),
                "COPYBOOK だけを対象にすれば COBOL 本体の文は見ないこと");
    }

    private static String statementRuleFile(String target) {
        return """
                {
                  "version": 2,
                  "custom": [
                    {
                      "id": "U002",
                      "name": "ACCEPT の使用",
                      "commands": ["LINT"],
                      "targets": ["%s"],
                      "match": { "kind": "statement", "verb": "ACCEPT" },
                      "message": "ACCEPT は運用規約で禁止されている"
                    }
                  ]
                }
                """.formatted(target);
    }

    /** 利用者定義ルールの説明も SARIF の rules へ載り、読む側が指摘の意味を追える。 */
    @Test
    void userRuleAppearsInSarifRules(@TempDir Path dir) throws IOException {
        Path rules = prepare(dir, RULES_FILE);
        LintRunner.Result result = lint(dir, rules);
        assertTrue(result.sarifJson().contains("\"id\":\"U001\""), result.sarifJson());
        assertTrue(result.sarifJson().contains("コンソール入力の使用"), result.sarifJson());
    }
}
