package jp.cobolinsight.engineapi.source;

/**
 * 文字コード判別の結果。confidence は 0.0〜1.0。manualOverride は利用者の手動指定で確定した
 * ことを示す。soSiPresent は SO/SI(0x0E/0x0F)制御文字の検出有無。
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
