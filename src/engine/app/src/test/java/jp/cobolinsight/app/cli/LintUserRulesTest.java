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

/** Verifies that user-defined rules ride along the lint execution path. */
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
     * The statement kind also obeys targets. Runs the same definition targeting COBOL and targeting
     * COPYBOOK, and checks that an ACCEPT in a COBOL program shows up only for the former.
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

    /**
     * A rule may target an SQL script, and only a rule that says so judges one. The same line
     * pattern is run targeting SQL and targeting COBOL over a folder holding a script and a
     * program, and only the former reports. The built-in SQL advice is held to the same rule: the
     * script's statements reach every rule that reads SQL, and only the advice that declares
     * {@code AssetKind.SQL} may report on them.
     */
    @Test
    void lineRuleObeysTheSqlTarget(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("cobol"));
        Files.writeString(dir.resolve("cobol").resolve("USERRULE.cbl"), PROGRAM,
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("CSQKOZA.sql"), String.join("\n",
                "-- 口座マスタを全件読む確認用の問い合わせ。",
                "SELECT * FROM CSDB.CSQKOZA;",
                ""), StandardCharsets.UTF_8);
        Path rules = dir.resolve("rules.json");

        Files.writeString(rules, sqlRuleFile("SQL"), StandardCharsets.UTF_8);
        LintRunner.Result onSql = lint(dir, rules);
        List<Finding> found = userFindings(onSql);
        assertEquals(1, found.size(), () -> "SQL を対象にすれば出ること: " + found);
        assertEquals("CSQKOZA.sql", found.get(0).location().file().replace('\\', '/'));
        // S001 declares AssetKind.SQL and judges the script; S004, which stays COBOL-only, is
        // handed the script's statements in the same list and still has to be kept off it, which
        // is what the guard in Rules.apply does.
        assertEquals(List.of("S001"), onSql.sqlFindings().stream()
                        .filter(f -> f.location().file().endsWith(".sql"))
                        .map(Finding::ruleId).distinct().sorted().toList(),
                "SQL スクリプトを見る組み込みの助言は AssetKind.SQL を宣言したものだけであること");

        Files.writeString(rules, sqlRuleFile("COBOL"), StandardCharsets.UTF_8);
        assertEquals(List.of(), userFindings(lint(dir, rules)),
                "COBOL だけを対象にすれば SQL スクリプトの行は見ないこと");
    }

    private static String sqlRuleFile(String target) {
        return """
                {
                  "version": 2,
                  "custom": [
                    {
                      "id": "U003",
                      "name": "列を明示しない問い合わせ",
                      "commands": ["LINT"],
                      "targets": ["%s"],
                      "match": { "kind": "line", "regex": "SELECT\\\\s+\\\\*",
                                 "area": "wholeLine" },
                      "message": "列を明示してください"
                    }
                  ]
                }
                """.formatted(target);
    }

    /** A user-defined rule's description also lands in SARIF's rules, so a reader can follow the finding's meaning. */
    @Test
    void userRuleAppearsInSarifRules(@TempDir Path dir) throws IOException {
        Path rules = prepare(dir, RULES_FILE);
        LintRunner.Result result = lint(dir, rules);
        assertTrue(result.sarifJson().contains("\"id\":\"U001\""), result.sarifJson());
        assertTrue(result.sarifJson().contains("コンソール入力の使用"), result.sarifJson());
    }
}
