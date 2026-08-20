package jp.cobolinsight.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ルール設定ファイルの読み込みと、誤りを止める側と警告に回す側の切り分けの検証。 */
class RuleConfigTest {

    private static RuleConfig parse(String json) {
        return RuleConfig.parse(json, "rule-config.json");
    }

    @Test
    void readsDisabledRuleIds() {
        RuleConfig config = parse("{\"version\": 1, \"disabledRules\": [\"R001\", \"S002\"]}");
        assertEquals(Set.of("R001", "S002"), config.disabledRuleIds());
        assertEquals(List.of(), config.warnings());
    }

    @Test
    void emptyListDisablesNothing() {
        RuleConfig config = parse("{\"version\": 1, \"disabledRules\": []}");
        assertEquals(Set.of(), config.disabledRuleIds());
        assertEquals(List.of(), config.warnings());
    }

    /** disabledRules が無い設定は、全ルールを有効にした設定として扱う。 */
    @Test
    void missingDisabledRulesYieldsEmptyConfig() {
        assertEquals(Set.of(), parse("{\"version\": 1}").disabledRuleIds());
    }

    /** カタログに無いIDはここでは通す。照合できるのはカタログを持つ rules だけである。 */
    @Test
    void unknownRuleIdPassesThroughWithoutWarning() {
        RuleConfig config = parse("{\"disabledRules\": [\"R999\"]}");
        assertEquals(Set.of("R999"), config.disabledRuleIds());
        assertEquals(List.of(), config.warnings());
    }

    /** 1要素の誤りで残りを捨てない。利用者が誤りを直しながら使えるようにするためである。 */
    @Test
    void keepsValidIdsWhenOneEntryIsNotAString() {
        RuleConfig config = parse("{\"disabledRules\": [\"R001\", 7, \"\"]}");
        assertEquals(Set.of("R001"), config.disabledRuleIds());
        assertEquals(2, config.warnings().size());
        assertTrue(config.warnings().get(0).contains("disabledRules[1]"),
                config.warnings().get(0));
    }

    @Test
    void rejectsBrokenJson() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> parse("{\"disabledRules\": ["));
        assertTrue(e.getMessage().contains("rule-config.json"), e.getMessage());
    }

    @Test
    void rejectsUnsupportedVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("{\"version\": 2, \"disabledRules\": []}"));
    }

    @Test
    void rejectsDisabledRulesThatIsNotAnArray() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"disabledRules\": \"R001\"}"));
    }

    /** 指定が無い場合だけが空の設定である。指定した綴りの誤りは黙って流さない。 */
    @Test
    void noFileYieldsEmptyConfig() {
        assertEquals(Set.of(), RuleConfig.load(null).disabledRuleIds());
    }

    @Test
    void missingFileIsAnError(@TempDir Path dir) {
        Path absent = dir.resolve("absent.json");
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> RuleConfig.load(absent));
        assertTrue(e.getMessage().contains("absent.json"), e.getMessage());
    }

    @Test
    void readsFileAsUtf8(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("rule-config.json");
        Files.writeString(file, "{\"version\": 1, \"disabledRules\": [\"U001\"]}",
                StandardCharsets.UTF_8);
        assertEquals(Set.of("U001"), RuleConfig.load(file).disabledRuleIds());
    }
}
