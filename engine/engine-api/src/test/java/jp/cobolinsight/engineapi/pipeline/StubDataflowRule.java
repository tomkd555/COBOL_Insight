package jp.cobolinsight.engineapi.pipeline;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;

import java.util.List;

/**
 * 規則をフェーズで絞り込めることを検査するためのスタブ。テストの
 * {@code META-INF/services/jp.cobolinsight.engineapi.spi.Rule} へ登録し、
 * {@link AnalysisPhase#DATA_FLOW} の規則として振る舞う。検出は行わない。
 */
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
