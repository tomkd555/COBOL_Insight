package jp.cobolinsight.persistence.model;

/** FINDING表の1行(SARIF準拠の検出結果)。 */
public record FindingRecord(long id, String ruleId, String level, long sourceId, int startLine,
        int startCol, long byteOffset, String message, String sarifJson) {

    public FindingRecord {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }
        if (level == null || level.isBlank()) {
            throw new IllegalArgumentException("level must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (sarifJson == null || sarifJson.isBlank()) {
            throw new IllegalArgumentException("sarifJson must not be blank");
        }
    }
}
