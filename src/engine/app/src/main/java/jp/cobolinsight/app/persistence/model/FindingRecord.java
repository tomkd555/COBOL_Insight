package jp.cobolinsight.app.persistence.model;

/**
 * One row of the FINDING table (a SARIF-compliant detection result). startLine and startCol are
 * 1-based, and byteOffset points to a position in the original byte sequence, holding -1 when unknown.
 */
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
