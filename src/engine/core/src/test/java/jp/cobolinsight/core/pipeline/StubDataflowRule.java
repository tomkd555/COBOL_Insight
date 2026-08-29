package jp.cobolinsight.core.pipeline;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.AnalysisPhase;
import jp.cobolinsight.core.spi.Rule;
import jp.cobolinsight.core.spi.RuleDoc;

import java.util.List;

/**
 * 規則をフェーズで絞り込めることを検査するためのスタブ。テストの
 * {@code META-INF/services/jp.cobolinsight.core.spi.Rule} へ登録し、
 * {@link AnalysisPhase#DATA_FLOW} の規則として振る舞う。検出は行わない。
 */
public final class StubDataflowRule implements Rule {

    @Override
    public String id() {
        return "R901";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("データフロー段階のスタブ", "テスト")
                .summary("検査用のスタブであり、検出は行わない。")
                .rationale("解析段階でルールを絞り込めることだけを確かめる。")
                .detection("常に検出なし。")
                .remedy("対処は不要である。")
                .build();
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
