package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.pipeline.AnalysisServices;
import jp.cobolinsight.core.spi.AnalysisPhase;
import jp.cobolinsight.core.spi.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQL指摘6ルール(S001〜S006)の ServiceLoader 登録・ID順・severity・段階の検証。 */
class SqlRuleRegistrationTest {

    private static final Map<String, Severity> EXPECTED = Map.ofEntries(
            entry("S001", Severity.MEDIUM),
            entry("S002", Severity.HIGH),
            entry("S003", Severity.HIGH),
            entry("S004", Severity.MEDIUM),
            entry("S005", Severity.LOW),
            entry("S006", Severity.LOW));

    private static List<Rule> sqlRules() {
        return AnalysisServices.load().rules(AnalysisPhase.SYNTAX).stream()
                .filter(rule -> rule.id().startsWith("S"))
                .toList();
    }

    @Test
    void sixSqlAdviceRulesDiscoveredInIdOrder() {
        assertEquals(List.of("S001", "S002", "S003", "S004", "S005", "S006"),
                sqlRules().stream().map(Rule::id).toList());
    }

    @Test
    void severityPhaseAndFixProducerMatchCatalog() {
        for (Rule rule : sqlRules()) {
            assertEquals(EXPECTED.get(rule.id()), rule.defaultSeverity(), rule.id());
            assertEquals(AnalysisPhase.SYNTAX, rule.phase(), rule.id());
            assertTrue(rule.fixProducer().isEmpty(), rule.id() + " は修正案生成の対象外であること");
        }
    }
}
