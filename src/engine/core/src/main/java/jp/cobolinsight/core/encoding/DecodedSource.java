package jp.cobolinsight.core.encoding;

/**
 * Output of source ingestion. Bundles the decoded text (normalized to UTF-16), the original byte
 * array, the offset table, and the determined encoding information.
 *
 * @param text          decoded text (internally normalized to UTF-16)
 * @param originalBytes original byte array
 * @param offsetTable   table mapping post-decode character positions to original byte offsets
 * @param encodingInfo  determined code page, confidence, SO/SI presence, and whether it was manually specified
 */
public record DecodedSource(String text, byte[] originalBytes, ByteOffsetTable offsetTable, EncodingInfo encodingInfo) {
}
