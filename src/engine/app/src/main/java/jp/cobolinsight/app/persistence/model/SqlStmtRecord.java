package jp.cobolinsight.app.persistence.model;

/**
 * One row of the SQL_STMT table (embedded SQL). mangledText is the statement with host variables
 * replaced by sequential tokens so it can be passed to the parser; originalText is the original
 * text before replacement.
 */
public record SqlStmtRecord(long id, long sourceId, String stmtType, String mangledText,
        String originalText) {

    public SqlStmtRecord {
        if (stmtType == null || stmtType.isBlank()) {
            throw new IllegalArgumentException("stmtType must not be blank");
        }
        if (mangledText == null || mangledText.isBlank()) {
            throw new IllegalArgumentException("mangledText must not be blank");
        }
        if (originalText == null || originalText.isBlank()) {
            throw new IllegalArgumentException("originalText must not be blank");
        }
    }
}
