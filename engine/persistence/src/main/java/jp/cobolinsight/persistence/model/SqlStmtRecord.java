package jp.cobolinsight.persistence.model;

/** SQL_STMT表の1行(埋め込みSQL)。mangledTextはホスト変数マングリング後の文。 */
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
