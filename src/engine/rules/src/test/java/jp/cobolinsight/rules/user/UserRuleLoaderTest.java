package jp.cobolinsight.rules.user;

import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 利用者定義ルールの読み込みと、定義の誤りを利用者へ返す経路の検証。 */
class UserRuleLoaderTest {

    private static final String ONE_RULE = """
            {
              "version": 1,
              "rules": [
                {
                  "id": "U001",
                  "name": "コンソール入力の使用",
                  "category": "社内規約",
                  "severity": "HIGH",
                  "targets": ["COBOL"],
                  "pattern": "ACCEPT\\\\s+\\\\S+\\\\s+FROM\\\\s+CONSOLE",
                  "ignoreCase": true,
                  "message": "コンソール入力は運用規約で禁止されている",
                  "rationale": "運用手順の外で値が入り、記録が残らない。",
                  "remedy": "入力をパラメータファイルから受け取る。"
                }
              ]
            }
            """;

    private static UserRuleLoader.LoadResult parse(String json) {
        return UserRuleLoader.parse(json, "user-rules.json");
    }

    @Test
    void readsSingleRuleWithAllFields() {
        UserRuleLoader.LoadResult result = parse(ONE_RULE);
        assertEquals(List.of(), result.errors());
        assertEquals(1, result.rules().size());
        Rule rule = result.rules().get(0);
        assertEquals("U001", rule.id());
        assertEquals(Severity.HIGH, rule.defaultSeverity());
        assertEquals(AnalysisPhase.SYNTAX, rule.phase());
        assertEquals("コンソール入力の使用", rule.doc().name());
        assertEquals("社内規約", rule.doc().category());
        assertEquals("運用手順の外で値が入り、記録が残らない。", rule.doc().rationale());
    }

    /** 検出条件は定義から機械的に組む。利用者が書いた説明と食い違わせないためである。 */
    @Test
    void detectionTextIsDerivedFromDefinition() {
        String detection = parse(ONE_RULE).rules().get(0).doc().detection();
        assertTrue(detection.contains("COBOL"), detection);
        assertTrue(detection.contains("ACCEPT"), detection);
        assertTrue(detection.contains("大小を区別しません"), detection);
    }

    @Test
    void appliesDefaultsForOptionalFields() {
        UserRuleLoader.LoadResult result = parse("""
                {"rules": [{"id": "U010", "name": "試験", "pattern": "GOBACK",
                 "message": "検出した"}]}
                """);
        assertEquals(List.of(), result.errors());
        Rule rule = result.rules().get(0);
        assertEquals(Severity.MEDIUM, rule.defaultSeverity());
        assertEquals("利用者定義", rule.doc().category());
    }

    @Test
    void rejectsIdNotStartingWithU() {
        UserRuleLoader.LoadResult result = parse("""
                {"rules": [{"id": "R001", "name": "試験", "pattern": "A", "message": "M"}]}
                """);
        assertEquals(List.of(), result.rules());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("R001"), result.errors().get(0));
    }

    @Test
    void rejectsDuplicateId() {
        UserRuleLoader.LoadResult result = parse("""
                {"rules": [
                  {"id": "U001", "name": "A", "pattern": "A", "message": "M"},
                  {"id": "U001", "name": "B", "pattern": "B", "message": "M"}]}
                """);
        assertEquals(1, result.rules().size());
        assertEquals(1, result.errors().size());
    }

    @Test
    void rejectsInvalidRegex() {
        UserRuleLoader.LoadResult result = parse("""
                {"rules": [{"id": "U002", "name": "試験", "pattern": "A(", "message": "M"}]}
                """);
        assertEquals(List.of(), result.rules());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("正規表現"), result.errors().get(0));
    }

    @Test
    void rejectsUnknownSeverity() {
        UserRuleLoader.LoadResult result = parse("""
                {"rules": [{"id": "U003", "name": "試験", "pattern": "A", "message": "M",
                 "severity": "CRITICAL"}]}
                """);
        assertEquals(List.of(), result.rules());
        assertEquals(1, result.errors().size());
    }

    @Test
    void rejectsUnknownTarget() {
        UserRuleLoader.LoadResult result = parse("""
                {"rules": [{"id": "U004", "name": "試験", "pattern": "A", "message": "M",
                 "targets": ["JCL"]}]}
                """);
        assertEquals(List.of(), result.rules());
        assertEquals(1, result.errors().size());
    }

    @Test
    void rejectsMissingRequiredField() {
        UserRuleLoader.LoadResult result = parse("""
                {"rules": [{"id": "U005", "name": "試験", "message": "M"}]}
                """);
        assertEquals(List.of(), result.rules());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("pattern"), result.errors().get(0));
    }

    /** 1件の誤りで残りを捨てない。利用者が誤りを直しながら使えるようにするためである。 */
    @Test
    void keepsValidRulesWhenOneIsInvalid() {
        UserRuleLoader.LoadResult result = parse("""
                {"rules": [
                  {"id": "U006", "name": "正しい", "pattern": "A", "message": "M"},
                  {"id": "bad", "name": "誤り", "pattern": "A", "message": "M"}]}
                """);
        assertEquals(1, result.rules().size());
        assertEquals("U006", result.rules().get(0).id());
        assertEquals(1, result.errors().size());
    }

    @Test
    void reportsBrokenJsonAsSingleError() {
        UserRuleLoader.LoadResult result = parse("{\"rules\": [");
        assertEquals(List.of(), result.rules());
        assertEquals(1, result.errors().size());
    }

    @Test
    void rejectsUnsupportedVersion() {
        UserRuleLoader.LoadResult result = parse("{\"version\": 2, \"rules\": []}");
        assertEquals(1, result.errors().size());
    }

    @Test
    void missingFileYieldsNoRulesAndNoError(@TempDir Path dir) {
        UserRuleLoader.LoadResult result = UserRuleLoader.load(dir.resolve("absent.json"));
        assertEquals(List.of(), result.rules());
        assertEquals(List.of(), result.errors());
    }

    @Test
    void readsFileAsUtf8(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("user-rules.json");
        Files.writeString(file, ONE_RULE, StandardCharsets.UTF_8);
        UserRuleLoader.LoadResult result = UserRuleLoader.load(file);
        assertEquals(List.of(), result.errors());
        assertEquals("コンソール入力の使用", result.rules().get(0).doc().name());
    }
}
