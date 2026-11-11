package jp.cobolinsight.core.rule;

import java.util.List;

/**
 * The categories of the built-in catalogue. A category says what goes wrong in production when the
 * finding is left alone, which is the question a reviewer triages by; the mechanism that detects it
 * (control flow, data flow, a keyword) is the rule's own business and is stated in its
 * {@code detection} text.
 *
 * <p>A user-defined rule may still name any category it likes; these are the ones the built-in
 * rules are filed under, in the order a report lists them.
 */
public final class RuleCategory {

    /** The run stops: an abend, a JCL error, a negative SQLCODE nobody handles. */
    public static final String ABEND = "異常終了";

    /** The run completes and the result is wrong: lost, truncated, duplicated or stale data. */
    public static final String INTEGRITY = "データ不整合";

    /** Elapsed time, CPU, locks, storage and other resources held longer than needed. */
    public static final String PERFORMANCE = "性能・資源";

    /** The job cannot be rerun, restarted or operated as written. */
    public static final String OPERATION = "運用・再実行";

    public static final String SECURITY = "セキュリティ";

    /** Nothing fails today; the next change is harder or riskier than it has to be. */
    public static final String MAINTAINABILITY = "保守性";

    private static final List<String> ALL =
            List.of(ABEND, INTEGRITY, PERFORMANCE, OPERATION, SECURITY, MAINTAINABILITY);

    private RuleCategory() {
    }

    /** Every built-in category, most urgent first. */
    public static List<String> all() {
        return ALL;
    }
}
