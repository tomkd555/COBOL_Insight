package jp.cobolinsight.core.encoding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies EBCDIC estimation and decoding. Since no real-machine EBCDIC file is available, the
 * input is a byte array obtained by re-converting a UTF-8 sample into each EBCDIC code page.
 * Because auto-detection does not distinguish IBM930 from IBM939, the estimation side only
 * asserts that the encoding is EBCDIC, not that the code page matches exactly.
 */
class EbcdicEstimationTest {

    private static final Path UTF8_SAMPLE =
            Path.of("..", "..", "..", "samples", "encoding", "SYKENC1_UTF8.cbl");

    private final CodePageDetector detector = new CodePageDetector();
    private final SourceDecoder decoder = new SourceDecoder();

    private static String sampleText() throws IOException {
        return new String(Files.readAllBytes(UTF8_SAMPLE), StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @EnumSource(value = CodePage.class, names = {"IBM930", "IBM939"})
    void syntheticEbcdicBytesAreEstimatedAsEbcdic(CodePage codePage) throws IOException {
        byte[] ebcdic = sampleText().getBytes(codePage.charset());

        DetectionResult result = detector.detect(ebcdic);

        assertTrue(result.codePage().isEbcdic());
        assertTrue(result.estimated());
        assertTrue(result.soSiPresent());
    }

    @ParameterizedTest
    @EnumSource(value = CodePage.class, names = {"IBM930", "IBM939"})
    void manualCodePageDecodesAndRoundTripsSyntheticEbcdicBytes(CodePage codePage) throws IOException {
        String original = sampleText();
        byte[] ebcdic = original.getBytes(codePage.charset());

        DecodedSource source = decoder.decode(ebcdic, codePage);

        assertEquals(original, source.text());
        assertArrayEquals(ebcdic, source.text().getBytes(codePage.charset()));
        assertArrayEquals(ebcdic, source.originalBytes());
    }
}
