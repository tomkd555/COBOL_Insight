package jp.cobolinsight.persistence.model;

/** SOURCE表の1行(COBOL本体・コピー句・JCLいずれかのソースファイル)。 */
public record SourceRecord(long id, String path, String codepage, String contentHash, long byteSize) {

    public SourceRecord {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash must not be blank");
        }
    }
}
