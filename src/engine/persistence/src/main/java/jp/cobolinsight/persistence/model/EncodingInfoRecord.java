package jp.cobolinsight.persistence.model;

/** ENCODING_INFO表の1行(SOURCEと1:1)。 */
public record EncodingInfoRecord(long sourceId, String detectedCharset, double confidence,
        boolean manualOverride, boolean soSiPresent) {

    public EncodingInfoRecord {
        if (detectedCharset == null || detectedCharset.isBlank()) {
            throw new IllegalArgumentException("detectedCharset must not be blank");
        }
    }
}
