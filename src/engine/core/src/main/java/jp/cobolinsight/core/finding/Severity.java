package jp.cobolinsight.core.finding;

/**
 * Severity vocabulary for the rule catalog. High=HIGH, Medium=MEDIUM, Low=LOW,
 * Recommended=ADVISORY (R009/R025 only). Maps to SARIF level as HIGH to error,
 * MEDIUM/ADVISORY to warning, LOW to note.
 */
public enum Severity {
    HIGH,
    MEDIUM,
    LOW,
    ADVISORY;

    /** The Japanese label name shown by the GUI and CLI. */
    public String label() {
        return switch (this) {
            case HIGH -> "高";
            case MEDIUM -> "中";
            case LOW -> "低";
            case ADVISORY -> "推奨";
        };
    }

    public FindingLevel toLevel() {
        return switch (this) {
            case HIGH -> FindingLevel.ERROR;
            case MEDIUM, ADVISORY -> FindingLevel.WARNING;
            case LOW -> FindingLevel.NOTE;
        };
    }
}
