package jp.cobolinsight.core.encoding;

import jp.cobolinsight.core.spi.CharsetProvider;

/**
 * Implementation of engine-api's {@link CharsetProvider}. Converts results from this module's
 * {@link SourceDecoder} into engine-api's DecodedSource / EncodingInfo. Confidence is normalized
 * from 0-100 to 0.0-1.0.
 */
public final class EncodingCharsetProvider implements CharsetProvider {

    private final SourceDecoder decoder = new SourceDecoder();

    @Override
    public jp.cobolinsight.core.source.DecodedSource decode(String path, byte[] bytes) {
        return toEngineApi(path, decoder.decode(bytes));
    }

    @Override
    public jp.cobolinsight.core.source.DecodedSource decode(String path, byte[] bytes,
            String charsetName) {
        return toEngineApi(path, decoder.decode(bytes, CodePage.fromName(charsetName)));
    }

    private static jp.cobolinsight.core.source.DecodedSource toEngineApi(String path,
            DecodedSource source) {
        // engine-api's DecodedSource requires charByteOffsets' length to match text's length, so
        // the trailing total-byte-length element that ByteOffsetTable carries is excluded.
        int[] charByteOffsets = new int[source.text().length()];
        for (int i = 0; i < charByteOffsets.length; i++) {
            charByteOffsets[i] = source.offsetTable().byteOffsetOfChar(i);
        }
        EncodingInfo info = source.encodingInfo();
        double confidence = Math.min(1.0, Math.max(0.0, info.confidence() / 100.0));
        return new jp.cobolinsight.core.source.DecodedSource(path, source.text(),
                source.originalBytes(), charByteOffsets,
                new jp.cobolinsight.core.source.EncodingInfo(info.codePage().charsetName(),
                        confidence, info.manualOverride(), info.soSiPresent()));
    }
}
