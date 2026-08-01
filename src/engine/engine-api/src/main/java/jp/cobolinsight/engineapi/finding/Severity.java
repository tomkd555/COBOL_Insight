package jp.cobolinsight.engineapi.finding;

/**
 * ルールカタログの severity 語彙。高=HIGH、中=MEDIUM、低=LOW、推奨=ADVISORY(R009・R025 専用)。
 * SARIF level へは HIGH→error、MEDIUM・ADVISORY→warning、LOW→note と対応づける。
 */
public enum Severity {
    HIGH,
    MEDIUM,
    LOW,
    ADVISORY;

    /** 画面と CLI が示す日本語の呼び名。 */
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
