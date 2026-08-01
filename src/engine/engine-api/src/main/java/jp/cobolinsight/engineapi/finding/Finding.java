package jp.cobolinsight.engineapi.finding;

import jp.cobolinsight.engineapi.source.SourcePosition;

import java.util.List;
import java.util.Objects;

/**
 * SARIF 2.1.0 準拠の検出結果。物理位置は {@link SourcePosition}(URI 相当のファイル・開始行・
 * 開始桁・原バイトオフセット)で保持する。
 */
public record Finding(String ruleId, FindingLevel level, String message, SourcePosition location,
        List<CodeFlow> codeFlows, List<FixSuggestion> fixes) {

    /** パース失敗を error レベルの finding として記録するときのルールID。 */
    public static final String PARSE_FAILURE_RULE_ID = "parse-failure";

    public Finding {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }
        Objects.requireNonNull(level, "level");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        Objects.requireNonNull(location, "location");
        codeFlows = List.copyOf(codeFlows);
        fixes = List.copyOf(fixes);
    }

    public static Finding of(String ruleId, FindingLevel level, String message,
            SourcePosition location) {
        return new Finding(ruleId, level, message, location, List.of(), List.of());
    }

    public static Finding parseFailure(SourcePosition location, String message) {
        return of(PARSE_FAILURE_RULE_ID, FindingLevel.ERROR, message, location);
    }
}
