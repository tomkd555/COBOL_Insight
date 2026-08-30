package jp.cobolinsight.core.encoding;

/**
 * Result of character-code detection.
 *
 * @param codePage    the detected or estimated code page
 * @param confidence  confidence (0-100)
 * @param soSiPresent whether SO/SI (0x0E/0x0F) is present
 * @param estimated   whether this is an estimate rather than content-based detection (EBCDIC estimate or fallback)
 */
public record DetectionResult(CodePage codePage, int confidence, boolean soSiPresent, boolean estimated) {
}
