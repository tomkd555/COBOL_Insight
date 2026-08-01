package jp.cobolinsight.rules;

import jp.cobolinsight.engineapi.pipeline.AnalysisServices;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全ルールが説明を持つことの検証。説明は CLI の rules サブコマンド・SARIF・GUI の設定画面が
 * 引く単一の正であり、欠けたルールがあると画面に「未登録のルール」として現れる。
 */
class RuleDocCoverageTest {

    private static List<Rule> allRules() {
        return AnalysisServices.load().rules();
    }

    @Test
    void thirtySevenRulesAreRegistered() {
        assertEquals(37, allRules().size());
    }

    @Test
    void everyRuleProvidesDoc() {
        for (Rule rule : allRules()) {
            RuleDoc doc = rule.doc();
            assertTrue(!doc.name().isBlank(), rule.id() + " の名称が空である");
            assertTrue(!doc.category().isBlank(), rule.id() + " のカテゴリが空である");
            assertTrue(!doc.summary().isBlank(), rule.id() + " の要約が空である");
            assertTrue(!doc.rationale().isBlank(), rule.id() + " の理由が空である");
            assertTrue(!doc.detection().isBlank(), rule.id() + " の検出条件が空である");
            assertTrue(!doc.remedy().isBlank(), rule.id() + " の対処が空である");
        }
    }

    /** 名称は画面の一覧と絞り込みで使うため、ルールを名称で言い分けられる必要がある。 */
    @Test
    void ruleNamesAreUnique() {
        Set<String> seen = new HashSet<>();
        List<String> duplicated = new ArrayList<>();
        for (Rule rule : allRules()) {
            if (!seen.add(rule.doc().name())) {
                duplicated.add(rule.doc().name());
            }
        }
        assertEquals(List.of(), duplicated);
    }

    @Test
    void everyRuleProvidesBothSidesOfExample() {
        List<String> missing = allRules().stream()
                .filter(rule -> !rule.doc().hasExample())
                .map(Rule::id)
                .toList();
        assertEquals(List.of(), missing);
    }

    /** ID は検出ルールが R、SQL 指摘が S で始まる。lint と sql-lint はこの接頭辞で振り分ける。 */
    @Test
    void ruleIdsUseKnownPrefixes() {
        for (Rule rule : allRules()) {
            assertTrue(rule.id().startsWith("R") || rule.id().startsWith("S"),
                    "想定外の接頭辞: " + rule.id());
        }
    }
}
