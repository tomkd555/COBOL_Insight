package jp.cobolinsight.app.persistence.model;

/**
 * SQL_STMT表の1行(埋め込みSQL)。mangledTextはホスト変数を連番トークンへ置き換えて構文解析器へ
 * 渡せる形にした文、originalTextは置換前の原文である。
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
