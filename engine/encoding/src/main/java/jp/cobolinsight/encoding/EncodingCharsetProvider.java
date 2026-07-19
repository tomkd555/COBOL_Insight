package jp.cobolinsight.encoding;

import jp.cobolinsight.engineapi.spi.CharsetProvider;

import java.util.Locale;

/**
 * engine-api の {@link CharsetProvider} 実装。モジュール内の {@link SourceDecoder} の結果を
 * engine-api の DecodedSource / EncodingInfo へ写像する。確信度は 0〜100 を 0.0〜1.0 へ正規化する。
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
        return toEngineApi(path, decoder.decode(bytes, codePageOf(charsetName)));
    }

    private static CodePage codePageOf(String charsetName) {
        String key = charsetName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return switch (key) {
            case "utf8" -> CodePage.UTF_8;
            case "shiftjis", "sjis", "windows31j", "ms932", "cp932", "932" -> CodePage.SHIFT_JIS;
            case "ibm930", "xibm930", "cp930", "930" -> CodePage.IBM930;
            case "ibm939", "xibm939", "cp939", "939" -> CodePage.IBM939;
            default -> throw new IllegalArgumentException("未対応のコードページ指定: " + charsetName);
        };
    }

    private static jp.cobolinsight.engineapi.source.DecodedSource toEngineApi(String path,
            DecodedSource source) {
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
