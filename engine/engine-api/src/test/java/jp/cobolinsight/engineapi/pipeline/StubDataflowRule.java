package jp.cobolinsight.engineapi.pipeline;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;

import java.util.List;

public final class StubDataflowRule implements Rule {

    @Override
    public String id() {
        return "R901";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ADVISORY;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.DATA_FLOW;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        return List.of();
    }
}
