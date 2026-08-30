package jp.cobolinsight.core.encoding;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies detection and decoding of the bundled samples. 993 characters is the sample's
 * character count, and 1,124 bytes is the Shift_JIS version's file length; both are values
 * measured from the sample.
 */
class SampleDecodingTest {

    /** The test's working directory is directly under the module, so this is anchored two levels up, at the repository root. */
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

    @Test
    void ebcdicSamplesDecodeToTheUtf8TextWithManualOverride() throws IOException {
        String expected = decoder.decode(Files.readAllBytes(SAMPLES.resolve("SYKENC1_UTF8.cbl"))).text();
        for (var cp : java.util.Map.of("SYKENC1_CP930.cbl", CodePage.IBM930, "SYKENC1_CP939.cbl", CodePage.IBM939).entrySet()) {
            byte[] bytes = Files.readAllBytes(SAMPLES.resolve(cp.getKey()));
            assertEquals(1_152, bytes.length, cp.getKey());
            DecodedSource source = decoder.decode(bytes, cp.getValue());
            assertEquals(expected, source.text(), cp.getKey() + " must decode to the UTF-8 original");
            assertTrue(decoder.decode(bytes).encodingInfo().codePage().isEbcdic(), cp.getKey() + " must be estimated as EBCDIC");
        }
    }
}
