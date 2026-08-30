package jp.cobolinsight.core.encoding;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies conversion to engine-api's CharsetProvider SPI. */
class EncodingCharsetProviderTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples", "encoding");

    private final EncodingCharsetProvider provider = new EncodingCharsetProvider();

    @Test
    void utf8AndShiftJisSamplesDecodeToTheSameText() throws IOException {
        byte[] utf8 = Files.readAllBytes(SAMPLES.resolve("SYKENC1_UTF8.cbl"));
        byte[] sjis = Files.readAllBytes(SAMPLES.resolve("SYKENC1_SJIS.cbl"));

        var utf8Source = provider.decode("SYKENC1_UTF8.cbl", utf8);
        var sjisSource = provider.decode("SYKENC1_SJIS.cbl", sjis);

        assertEquals(utf8Source.text(), sjisSource.text());
        assertEquals(993, utf8Source.text().length());
        assertEquals(1_124, sjis.length);
        assertEquals("UTF-8", utf8Source.encoding().detectedCharset());
        assertEquals("windows-31j", sjisSource.encoding().detectedCharset());
        assertFalse(utf8Source.encoding().manualOverride());
    }

    @Test
    void manualOverrideDecodesWithTheGivenCodePage() throws IOException {
        byte[] sjis = Files.readAllBytes(SAMPLES.resolve("SYKENC1_SJIS.cbl"));

        var source = provider.decode("SYKENC1_SJIS.cbl", sjis, "Shift_JIS");

        assertTrue(source.encoding().manualOverride());
        assertEquals("windows-31j", source.encoding().detectedCharset());
        assertEquals(1.0, source.encoding().confidence());
    }

    @Test
    void charByteOffsetsMapEachCharToItsOriginalByte() {
        byte[] bytes = "AB\nC".getBytes(StandardCharsets.UTF_8);

        var source = provider.decode("x.txt", bytes);

        assertEquals("AB\nC", source.text());
        assertEquals(0, source.byteOffsetAt(0));
        assertEquals(1, source.byteOffsetAt(1));
        assertEquals(2, source.byteOffsetAt(2));
        assertEquals(3, source.byteOffsetAt(3));
    }

    @Test
    void confidenceIsNormalizedToUnitInterval() throws IOException {
        byte[] utf8 = Files.readAllBytes(SAMPLES.resolve("SYKENC1_UTF8.cbl"));

        var source = provider.decode("SYKENC1_UTF8.cbl", utf8);

        assertTrue(source.encoding().confidence() > 0.0);
        assertTrue(source.encoding().confidence() <= 1.0);
    }

    @Test
    void unknownCharsetNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> provider.decode("x.txt", new byte[] {0x41}, "EUC-KR"));
    }
}
