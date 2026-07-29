package jp.cobolinsight.engineapi.pipeline;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;

import java.util.List;

/**
 * ServiceLoader による規則の発見を検査するためのスタブ。テストの
 * {@code META-INF/services/jp.cobolinsight.engineapi.spi.Rule} へ登録し、
 * {@link AnalysisPhase#SYNTAX} の規則として振る舞う。検出は行わない。
 */
public final class StubSyntaxRule implements Rule {

    @Override
    public String id() {
        return "R900";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.HIGH;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.SYNTAX;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        return List.of();
    }
}
