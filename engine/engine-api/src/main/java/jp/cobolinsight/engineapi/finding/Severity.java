package jp.cobolinsight.engineapi.finding;

/**
 * ルールカタログの severity 語彙。高=HIGH、中=MEDIUM、低=LOW、警告=ADVISORY(R009・R025 専用)。
 * SARIF level へは HIGH→error、MEDIUM・ADVISORY→warning、LOW→note と対応づける。
 */
public enum Severity {
    HIGH,
    MEDIUM,
    LOW,
    ADVISORY;

    public FindingLevel toLevel() {
        return switch (this) {
            case HIGH -> FindingLevel.ERROR;
            case MEDIUM, ADVISORY -> FindingLevel.WARNING;
            case LOW -> FindingLevel.NOTE;
        };
    }
}
