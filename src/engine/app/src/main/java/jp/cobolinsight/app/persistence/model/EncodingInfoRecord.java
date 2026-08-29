package jp.cobolinsight.app.persistence.model;

/** One row of the ENCODING_INFO table (1:1 with SOURCE). */
public record EncodingInfoRecord(long sourceId, String detectedCharset, double confidence,
        boolean manualOverride, boolean soSiPresent) {

    public EncodingInfoRecord {
        if (detectedCharset == null || detectedCharset.isBlank()) {
            throw new IllegalArgumentException("detectedCharset must not be blank");
        }
    }
}
