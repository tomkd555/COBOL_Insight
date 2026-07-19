package jp.cobolinsight.engineapi.pipeline;

import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
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
        assertEquals(1, services.charsetProviders().size());
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
