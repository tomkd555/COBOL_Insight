package jp.cobolinsight.core.pipeline;

import jp.cobolinsight.core.spi.AnalysisPhase;
import jp.cobolinsight.core.spi.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisServicesTest {

    @Test
    void loadDiscoversServicesRegisteredInMetaInf() {
        AnalysisServices services = AnalysisServices.load();
        assertEquals(List.of("R900", "R901"),
                services.rules().stream().map(Rule::id).toList());
        // The stub from test resources plus the real provider, which now lives in this module.
        assertEquals(2, services.charsetProviders().size());
        // engine-api のテスト実行時には構文解析器の実装がclasspathに無いため、パーサーは空になる
        assertTrue(services.cobolParsers().isEmpty());
        assertTrue(services.jclParsers().isEmpty());
        assertTrue(services.sqlParsers().isEmpty());
    }

    @Test
    void rulesCanBeFilteredByPhase() {
        AnalysisServices services = AnalysisServices.load();
        assertEquals(List.of("R900"),
                services.rules(AnalysisPhase.SYNTAX).stream().map(Rule::id).toList());
        assertEquals(List.of("R901"),
                services.rules(AnalysisPhase.DATA_FLOW).stream().map(Rule::id).toList());
        assertTrue(services.rules(AnalysisPhase.CONTROL_FLOW).isEmpty());
    }

    @Test
    void duplicateRuleIdsAreRejected() {
        Rule a = new StubSyntaxRule();
        Rule b = new StubSyntaxRule();
        assertThrows(IllegalStateException.class, () -> new AnalysisServices(
                List.of(), List.of(), List.of(), List.of(), List.of(a, b)));
    }

    @Test
    void serviceListsAreImmutable() {
        AnalysisServices services = AnalysisServices.load();
        assertThrows(UnsupportedOperationException.class,
                () -> services.rules().add(new StubSyntaxRule()));
    }
}
