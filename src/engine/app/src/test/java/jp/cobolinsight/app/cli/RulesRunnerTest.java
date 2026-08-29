package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.json.JsonReader;
import jp.cobolinsight.rules.RuleSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `rules` サブコマンドの出力の検証。GUI はこの JSON をルールカタログの供給源とする。 */
class RulesRunnerTest {

    private static final String CUSTOM_RULE = """
            {
              "version": 2,
              "custom": [
                {
                  "id": "U001",
                  "name": "自社で禁じた命令",
                  "category": "社内規約",
                  "summary": "ACCEPT を使っている。",
                  "severity": "LOW",
                  "match": { "kind": "line", "regex": "ACCEPT" },
                  "message": "運用規約で禁じている"
                }
              ]
            }
            """;

    private static RulesRunner.Result run(Path rulesFile, String ruleId) {
        return RulesRunner.run(new RulesRunner.Options(RuleSet.load(rulesFile), ruleId));
    }

    private static Path write(Path dir, String json) throws IOException {
        Path file = dir.resolve("rules.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static Map<String, Object> jsonOf(RulesRunner.Result result) {
        return JsonReader.asObject(JsonReader.parse(result.toJson()));
    }

    private static List<Object> rulesOf(RulesRunner.Result result) {
        return JsonReader.asArray(jsonOf(result).get("rules"));
    }

    @Test
    void listsBuiltinRulesWithDescriptions() {
        RulesRunner.Result result = run(null, null);
        assertEquals(34, result.rules().size());
        Map<String, Object> first = JsonReader.asObject(rulesOf(result).get(0));
        assertEquals("R001", first.get("id"));
        assertEquals("未初期化変数の参照", first.get("name"));
        assertEquals("HIGH", first.get("severity"));
        assertEquals("builtin", first.get("source"));
        assertTrue(!((String) first.get("remedy")).isBlank());
    }

    @Test
    void ruleIdSelectsSingleRuleWithDetailFlag() {
        RulesRunner.Result result = run(null, "R004");
        assertEquals(1, result.rules().size());
        assertTrue(result.detail());
        assertTrue(result.toText().contains("ON SIZE ERROR句の欠如"), result.toText());
        assertTrue(result.toText().contains("修正案: あり"), result.toText());
    }

    @Test
    void unknownRuleIdYieldsEmptySelection() {
        RulesRunner.Result result = run(null, "R999");
        assertEquals(List.of(), result.rules());
        assertTrue(result.detail());
    }

    @Test
    void customRulesJoinTheCatalog(@TempDir Path dir) throws IOException {
        RulesRunner.Result result = run(write(dir, CUSTOM_RULE), null);
        assertEquals(35, result.rules().size());
        Map<String, Object> custom = rulesOf(result).stream()
                .map(JsonReader::asObject)
                .filter(rule -> "U001".equals(rule.get("id")))
                .findFirst()
                .orElseThrow();
        assertEquals("user", custom.get("source"));
        assertEquals("社内規約", custom.get("category"));
        assertEquals(List.of("LINT", "REPORT"), JsonReader.asArray(custom.get("commands")));
        assertEquals(Boolean.FALSE, custom.get("hasFix"));
    }

    @Test
    void invalidCustomRuleIsReportedWithoutStoppingTheList(@TempDir Path dir) throws IOException {
        RulesRunner.Result result =
                run(write(dir, "{\"version\": 2, \"custom\": [{\"id\": \"bad\"}]}"), null);
        assertEquals(34, result.rules().size());
        assertEquals(1, result.errors().size());
        assertTrue(result.toText().contains("警告"), result.toText());
    }

    /** GUI はルールの説明と有効・無効を同じ JSON から読む。engine が唯一の供給源であるためである。 */
    @Test
    void disabledRulesAreMarkedInTheCatalog(@TempDir Path dir) throws IOException {
        RulesRunner.Result result = run(write(dir,
                "{\"version\": 2, \"rules\": {\"R004\": {\"enabled\": false}}}"), null);
        Map<String, Boolean> enabled = rulesOf(result).stream()
                .map(JsonReader::asObject)
                .collect(Collectors.toMap(rule -> (String) rule.get("id"),
                        rule -> (Boolean) rule.get("enabled")));
        assertEquals(Boolean.FALSE, enabled.get("R004"));
        assertEquals(Boolean.TRUE, enabled.get("R001"));
        assertEquals(List.of(), JsonReader.asArray(jsonOf(result).get("ruleErrors")));
    }

    /**
     * 設定が無ければ、既定で無効なルール以外はすべて有効である。R008 は計測の結果として既定で
     * 無効であり(corpus/rule-hits.md)、設定ファイルが無い状態でもその1件だけは無効で出る。
     */
    @Test
    void onlyTheDefaultOffRuleIsDisabledWithoutRulesFile() {
        RulesRunner.Result result = run(null, null);
        Map<String, Boolean> enabled = rulesOf(result).stream()
                .map(JsonReader::asObject)
                .collect(Collectors.toMap(rule -> (String) rule.get("id"),
                        rule -> (Boolean) rule.get("enabled")));
        assertEquals(List.of("R008"), enabled.entrySet().stream()
                .filter(entry -> !entry.getValue()).map(Map.Entry::getKey).sorted().toList());
    }

    /** ルールを入れ替えた後に設定へ取り残されたIDは、指定を捨てずに警告で知らせる。 */
    @Test
    void unknownRuleIdInTheFileIsReportedAsWarning(@TempDir Path dir) throws IOException {
        RulesRunner.Result result = run(write(dir,
                "{\"version\": 2, \"rules\": {\"R999\": {\"enabled\": false}}}"), null);
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("R999"), result.errors().get(0));
        assertTrue(result.toText().contains("警告: ルール設定"), result.toText());
    }

    @Test
    void textListingCountsBuiltinAndCustomRulesSeparately(@TempDir Path dir) throws IOException {
        String text = run(write(dir, CUSTOM_RULE), null).toText();
        assertTrue(text.contains("合計 35 件(組み込み 34・利用者定義 1)"), text);
    }
}
