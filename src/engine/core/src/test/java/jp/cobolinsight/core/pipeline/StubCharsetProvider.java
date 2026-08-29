package jp.cobolinsight.core.pipeline;

import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
import jp.cobolinsight.core.spi.CharsetProvider;

import java.nio.charset.StandardCharsets;

/**
 * 文字コードプロバイダの発見を検査するためのスタブ。テストの
 * {@code META-INF/services/jp.cobolinsight.core.spi.CharsetProvider} へ登録する。
 * 常に US-ASCII で復号し、文字位置とバイト位置を1対1に対応づける。
 */
public final class StubCharsetProvider implements CharsetProvider {

    @Override
    public DecodedSource decode(String path, byte[] bytes) {
        return decodeAscii(path, bytes, false);
    }

    @Override
    public DecodedSource decode(String path, byte[] bytes, String charsetName) {
        return decodeAscii(path, bytes, true);
    }

    /** manualOverride には、文字コードを明示する多重定義で呼ばれたかどうかを渡す。 */
    private static DecodedSource decodeAscii(String path, byte[] bytes, boolean manualOverride) {
        String text = new String(bytes, StandardCharsets.US_ASCII);
        int[] offsets = new int[text.length()];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = i;
        }
        EncodingInfo info = new EncodingInfo("US-ASCII", 1.0, manualOverride, false);
        return new DecodedSource(path, text, bytes, offsets, info);
    }
}
