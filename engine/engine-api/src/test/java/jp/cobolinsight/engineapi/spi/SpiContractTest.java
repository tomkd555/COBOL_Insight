package jp.cobolinsight.engineapi.spi;

import jp.cobolinsight.engineapi.bms.BmsMapset;
import jp.cobolinsight.engineapi.callgraph.CallGraph;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.source.SourcePosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpiContractTest {

    private static Finding errorFinding() {
        return Finding.parseFailure(SourcePosition.fileStart("A.cbl"), "boom");
    }

    @Test
    void parseOutcomeSuccessCarriesValue() {
        ParseOutcome<String> outcome = ParseOutcome.success("model");
        assertTrue(outcome.isSuccess());
        assertEquals("model", outcome.value().orElseThrow());
        assertTrue(outcome.failureFinding().isEmpty());
    }

    @Test
    void parseOutcomeFailureCarriesErrorFinding() {
        ParseOutcome<String> outcome = ParseOutcome.failure(errorFinding());
        assertTrue(!outcome.isSuccess());
        assertTrue(outcome.value().isEmpty());
        assertEquals(FindingLevel.ERROR, outcome.failureFinding().orElseThrow().level());
    }

    @Test
    void parseOutcomeFailureRejectsNonErrorFinding() {
        Finding warning = Finding.of("R009", FindingLevel.WARNING, "smell",
                SourcePosition.fileStart("A.cbl"));
        assertThrows(IllegalArgumentException.class, () -> ParseOutcome.failure(warning));
    }

    @Test
    void ruleFixProducerDefaultsToEmpty() {
        Rule rule = new Rule() {
            @Override
            public String id() {
                return "R999";
            }

            @Override
            public Severity defaultSeverity() {
                return Severity.LOW;
            }

            @Override
            public AnalysisPhase phase() {
                return AnalysisPhase.SYNTAX;
            }

            @Override
            public List<Finding> evaluate(AnalysisContext context) {
                return List.of();
            }
        };
        assertTrue(rule.fixProducer().isEmpty());
    }

    @Test
    void analysisPhaseCoversThreePhases() {
        assertEquals(3, AnalysisPhase.values().length);
    }

    @Test
    void analysisContextFactoryExposesModelsAndArtifacts() {
        CobolSemanticModel model = new CobolSemanticModel("PGMA", "A.cbl",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        CallGraph graph = new CallGraph(List.of(), List.of());
        AnalysisContext context = AnalysisContext.of(List.of(model), List.of(), List.of(),
                List.of(), Optional.of(graph), Map.of(String.class, "cfg-placeholder"));
        assertEquals("PGMA", context.cobolPrograms().get(0).programId());
        assertTrue(context.jclJobs().isEmpty());
        assertEquals(graph, context.callGraph().orElseThrow());
        assertEquals("cfg-placeholder", context.artifact(String.class).orElseThrow());
        assertTrue(context.artifact(Integer.class).isEmpty());
    }

    @Test
    void analysisContextRejectsArtifactValueOfWrongType() {
        assertThrows(IllegalArgumentException.class, () -> AnalysisContext.of(List.of(), List.of(),
                List.of(), List.<BmsMapset>of(), Optional.empty(), Map.of(Integer.class, "not-an-integer")));
    }

    @Test
    void analysisContextListsAreImmutable() {
        AnalysisContext context = AnalysisContext.of(List.of(), List.of(), List.of(),
                List.of(), Optional.empty(), Map.of());
        assertThrows(UnsupportedOperationException.class,
                () -> context.cobolPrograms().add(null));
    }
}
