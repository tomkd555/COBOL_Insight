package jp.cobolinsight.core.finding;

import jp.cobolinsight.core.source.SourcePosition;

import java.util.List;
import java.util.Objects;

/**
 * A SARIF 2.1.0 compliant detection result. The physical location is held as
 * {@link SourcePosition} (a URI-equivalent file, start line, start column, and original byte
 * offset).
 */
public record Finding(String ruleId, FindingLevel level, String message, SourcePosition location,
        List<CodeFlow> codeFlows, List<FixSuggestion> fixes) {

    /** Rule ID used when recording a parse failure as an error-level finding. */
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
