package jp.cobolinsight.core.source;

/**
 * The result of character encoding detection. confidence ranges 0.0-1.0. manualOverride
 * indicates the encoding was fixed by the user's manual specification. soSiPresent indicates
 * whether SO/SI (0x0E/0x0F) control characters were detected.
 */
public record EncodingInfo(String detectedCharset, double confidence, boolean manualOverride,
        boolean soSiPresent) {

    public EncodingInfo {
        if (detectedCharset == null || detectedCharset.isBlank()) {
            throw new IllegalArgumentException("detectedCharset must not be blank");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0.0, 1.0]: " + confidence);
        }
    }
}
