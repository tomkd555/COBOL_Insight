package jp.cobolinsight.engineapi.pipeline;

import jp.cobolinsight.engineapi.source.DecodedSource;
import jp.cobolinsight.engineapi.source.EncodingInfo;
import jp.cobolinsight.engineapi.spi.CharsetProvider;

import java.nio.charset.StandardCharsets;

public final class StubCharsetProvider implements CharsetProvider {

    @Override
    public DecodedSource decode(String path, byte[] bytes) {
        return decodeAscii(path, bytes, false);
    }

    @Override
    public DecodedSource decode(String path, byte[] bytes, String charsetName) {
        return decodeAscii(path, bytes, true);
    }

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
