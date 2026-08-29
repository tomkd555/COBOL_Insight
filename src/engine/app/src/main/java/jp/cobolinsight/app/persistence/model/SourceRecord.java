package jp.cobolinsight.app.persistence.model;

/**
 * One row of the SOURCE table (a source file that is COBOL, a copybook, or JCL). root is the
 * asset folder it was ingested from, and path is the relative path from that asset folder; a
 * single project file can hold rows from multiple asset folders.
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
