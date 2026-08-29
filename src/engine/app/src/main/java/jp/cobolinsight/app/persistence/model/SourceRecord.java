package jp.cobolinsight.app.persistence.model;

/**
 * SOURCE表の1行(COBOL本体・コピー句・JCLいずれかのソースファイル)。root は取込元の資産フォルダ、
 * path はその資産フォルダからの相対パスであり、1つのプロジェクトファイルは複数の資産フォルダの
 * 行を併せ持てる。
 */
public record SourceRecord(long id, String root, String path, String codepage, String contentHash,
        long byteSize) {

    public SourceRecord {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("root must not be blank");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash must not be blank");
        }
    }
}
