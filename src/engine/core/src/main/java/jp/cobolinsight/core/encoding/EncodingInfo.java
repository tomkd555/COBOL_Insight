package jp.cobolinsight.core.encoding;

/**
 * Encoding information used for decoding.
 *
 * @param codePage       the determined code page
 * @param confidence     confidence (0-100; a manual specification is 100)
 * @param soSiPresent    whether the original byte array contains SO/SI (0x0E/0x0F)
 * @param manualOverride whether a manual specification overrode automatic detection
 */
public record EncodingInfo(CodePage codePage, int confidence, boolean soSiPresent, boolean manualOverride) {
}
