package jp.cobolinsight.encoding;

import jp.cobolinsight.engineapi.spi.CharsetProvider;

/**
 * engine-api の {@link CharsetProvider} 実装。モジュール内の {@link SourceDecoder} の結果を
 * engine-api の DecodedSource / EncodingInfo へ変換する。確信度は 0〜100 を 0.0〜1.0 へ正規化する。
 */
public final class EncodingCharsetProvider implements CharsetProvider {

    private final SourceDecoder decoder = new SourceDecoder();

    @Override
    public jp.cobolinsight.engineapi.source.DecodedSource decode(String path, byte[] bytes) {
        return toEngineApi(path, decoder.decode(bytes));
    }

    @Override
    public jp.cobolinsight.engineapi.source.DecodedSource decode(String path, byte[] bytes,
            String charsetName) {
        return toEngineApi(path, decoder.decode(bytes, CodePage.fromName(charsetName)));
    }

    private static jp.cobolinsight.engineapi.source.DecodedSource toEngineApi(String path,
            DecodedSource source) {
        // engine-api の DecodedSource は charByteOffsets の長さと text の長さの一致を求めるため、
        // ByteOffsetTable が末尾に持つ全バイト長の要素は含めない。
        int[] charByteOffsets = new int[source.text().length()];
        for (int i = 0; i < charByteOffsets.length; i++) {
            charByteOffsets[i] = source.offsetTable().byteOffsetOfChar(i);
        }
        EncodingInfo info = source.encodingInfo();
        double confidence = Math.min(1.0, Math.max(0.0, info.confidence() / 100.0));
        return new jp.cobolinsight.engineapi.source.DecodedSource(path, source.text(),
                source.originalBytes(), charByteOffsets,
                new jp.cobolinsight.engineapi.source.EncodingInfo(info.codePage().charsetName(),
                        confidence, info.manualOverride(), info.soSiPresent()));
    }
}
