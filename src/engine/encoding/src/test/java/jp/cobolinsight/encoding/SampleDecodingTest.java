package jp.cobolinsight.encoding;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同梱サンプルの判別と復号の検証。993文字はサンプルの文字数、1,124バイトはShift_JIS版のファイル長で、
 * いずれもサンプルを測った値である。
 */
class SampleDecodingTest {

    /** テストの作業ディレクトリはモジュール直下であるため、2階層上のリポジトリ直下を起点にする。 */
    private static final Path SAMPLES = Path.of("..", "..", "..", "samples", "encoding");

    private final SourceDecoder decoder = new SourceDecoder();

    @Test
    void utf8SampleIsDetectedAndDecoded() throws IOException {
        byte[] bytes = Files.readAllBytes(SAMPLES.resolve("SYKENC1_UTF8.cbl"));
        DecodedSource source = decoder.decode(bytes);

        assertEquals(CodePage.UTF_8, source.encodingInfo().codePage());
        assertFalse(source.encodingInfo().manualOverride());
        assertTrue(source.encodingInfo().confidence() > 0);
        assertEquals(993, source.text().length());
    }

    @Test
    void shiftJisSampleIsDetectedAndDecoded() throws IOException {
        byte[] bytes = Files.readAllBytes(SAMPLES.resolve("SYKENC1_SJIS.cbl"));
        DecodedSource source = decoder.decode(bytes);

        assertEquals(CodePage.SHIFT_JIS, source.encodingInfo().codePage());
        assertFalse(source.encodingInfo().manualOverride());
        assertEquals(1_124, bytes.length);
        assertEquals(1_124, source.offsetTable().byteLength());
    }

    @Test
    void bothSamplesDecodeToTheSameNormalizedText() throws IOException {
        byte[] utf8 = Files.readAllBytes(SAMPLES.resolve("SYKENC1_UTF8.cbl"));
        byte[] sjis = Files.readAllBytes(SAMPLES.resolve("SYKENC1_SJIS.cbl"));

        assertEquals(decoder.decode(utf8).text(), decoder.decode(sjis).text());
    }
}
