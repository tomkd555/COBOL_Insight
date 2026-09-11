package jp.cobolinsight.app.persistence.model;

/**
 * One row of the FINDING table (a SARIF-compliant detection result). startLine and startCol are
 * 1-based.
 */
public record FindingRecord(long id, String ruleId, String level, long sourceId, int startLine,
        int startCol, String message) {

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
    }
}
