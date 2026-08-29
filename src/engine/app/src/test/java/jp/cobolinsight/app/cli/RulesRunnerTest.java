package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.json.JsonReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `rules` サブコマンドの出力の検証。GUI はこの JSON をルールカタログの供給源とする。 */
class RulesRunnerTest {

    private static final String USER_RULES = """
            {
              "version": 1,
              "rules": [
                {
                  "id": "U001",
                  "name": "自社で禁じた命令",
                  "category": "社内規約",
                  "severity": "LOW",
                  "pattern": "ACCEPT",
                  "message": "運用規約で禁じている"
                }
              ]
            }
            """;

    private static Map<String, Object> jsonOf(RulesRunner.Result result) {
        return JsonReader.asObject(JsonReader.parse(result.toJson()));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rulesOf(RulesRunner.Result result) {
        return JsonReader.asArray(jsonOf(result).get("rules"));
    }

    @Test
    void listsBuiltinRulesWithDescriptions() {
        RulesRunner.Result result = RulesRunner.run(new RulesRunner.Options(null, null));
        assertEquals(37, result.rules().size());
        Map<String, Object> first = JsonReader.asObject(rulesOf(result).get(0));
        assertEquals("R001", first.get("id"));
        assertEquals("未初期化変数の参照", first.get("name"));
        assertEquals("HIGH", first.get("severity"));
        assertEquals("builtin", first.get("source"));
        assertTrue(!((String) first.get("remedy")).isBlank());
    }

    @Test
    void ruleIdSelectsSingleRuleWithDetailFlag() {
        RulesRunner.Result result = RulesRunner.run(new RulesRunner.Options(null, "R004"));
        assertEquals(1, result.rules().size());
        assertTrue(result.detail());
        assertTrue(result.toText().contains("ON SIZE ERROR句の欠如"), result.toText());
        assertTrue(result.toText().contains("修正案: あり"), result.toText());
    }

    @Test
    void unknownRuleIdYieldsEmptySelection() {
        RulesRunner.Result result = RulesRunner.run(new RulesRunner.Options(null, "R999"));
        assertEquals(List.of(), result.rules());
        assertTrue(result.detail());
    }

    @Test
    void userRulesJoinTheCatalog(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("user-rules.json");
        Files.writeString(file, USER_RULES, StandardCharsets.UTF_8);
        RulesRunner.Result result = RulesRunner.run(new RulesRunner.Options(file, null));
        assertEquals(38, result.rules().size());
        Map<String, Object> user = rulesOf(result).stream()
                .map(JsonReader::asObject)
                .filter(rule -> "U001".equals(rule.get("id")))
                .findFirst()
                .orElseThrow();
        assertEquals("user", user.get("source"));
        assertEquals("社内規約", user.get("category"));
        assertEquals("SYNTAX", user.get("phase"));
        assertEquals(Boolean.FALSE, user.get("hasFix"));
    }

    @Test
    void invalidUserRuleIsReportedWithoutStoppingTheList(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("user-rules.json");
        Files.writeString(file, "{\"rules\": [{\"id\": \"bad\"}]}", StandardCharsets.UTF_8);
        RulesRunner.Result result = RulesRunner.run(new RulesRunner.Options(file, null));
        assertEquals(37, result.rules().size());
        assertEquals(1, result.userRuleErrors().size());
        assertTrue(result.toText().contains("警告"), result.toText());
    }

    /** GUI はルールの説明と有効・無効を同じ JSON から読む。engine が唯一の供給源であるためである。 */
    @Test
    void disabledRulesAreMarkedInTheCatalog() {
        RulesRunner.Result result =
                RulesRunner.run(new RulesRunner.Options(null, null, Set.of("R004")));
        Map<String, Boolean> enabled = rulesOf(result).stream()
                .map(JsonReader::asObject)
                .collect(Collectors.toMap(rule -> (String) rule.get("id"),
                        rule -> (Boolean) rule.get("enabled")));
        assertEquals(Boolean.FALSE, enabled.get("R004"));
        assertEquals(Boolean.TRUE, enabled.get("R001"));
        assertEquals(List.of(), JsonReader.asArray(jsonOf(result).get("ruleConfigWarnings")));
    }

    /** 設定が無ければ全ルールが有効である。 */
    @Test
    void everyRuleIsEnabledWithoutRuleConfig() {
        RulesRunner.Result result = RulesRunner.run(new RulesRunner.Options(null, null));
        assertTrue(rulesOf(result).stream().map(JsonReader::asObject)
                .allMatch(rule -> Boolean.TRUE.equals(rule.get("enabled"))));
    }

    /** ルールを入れ替えた後に設定へ取り残されたIDは、指定を捨てずに警告で知らせる。 */
    @Test
    void unknownDisabledIdIsReportedAsWarning() {
        RulesRunner.Result result =
                RulesRunner.run(new RulesRunner.Options(null, null, Set.of("R999")));
        assertEquals(1, result.ruleConfigWarnings().size());
        assertTrue(result.ruleConfigWarnings().get(0).contains("R999"),
                result.ruleConfigWarnings().get(0));
        assertTrue(result.toText().contains("警告: ルール設定"), result.toText());
    }

    @Test
    void textListingCountsBuiltinAndUserRulesSeparately(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("user-rules.json");
        Files.writeString(file, USER_RULES, StandardCharsets.UTF_8);
        String text = RulesRunner.run(new RulesRunner.Options(file, null)).toText();
        assertTrue(text.contains("合計 38 件(組み込み 37・利用者定義 1)"), text);
    }
}
