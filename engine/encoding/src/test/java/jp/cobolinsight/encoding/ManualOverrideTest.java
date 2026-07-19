package jp.cobolinsight.encoding;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualOverrideTest {

    private static final Path UTF8_SAMPLE =
            Path.of("..", "..", "samples", "encoding", "SYKENC1_UTF8.cbl");

    private final SourceDecoder decoder = new SourceDecoder();

    @Test
    void manualCodePageOverridesAutomaticDetection() throws IOException {
        String original = new String(Files.readAllBytes(UTF8_SAMPLE), StandardCharsets.UTF_8);
        byte[] ebcdic930 = original.getBytes(CodePage.IBM930.charset());

        DecodedSource auto = decoder.decode(ebcdic930);
        DecodedSource manual = decoder.decode(ebcdic930, CodePage.IBM939);

        assertTrue(auto.encodingInfo().codePage().isEbcdic());
        assertEquals(CodePage.IBM939, manual.encodingInfo().codePage());
        assertTrue(manual.encodingInfo().manualOverride());
        // CP930とCP939はSBCS面が異なるため、復号結果の相違が上書きの証跡になる
        assertNotEquals(decoder.decode(ebcdic930, CodePage.IBM930).text(), manual.text());
    }

    @Test
    void manualOverrideFlagDistinguishesManualFromAutomatic() throws IOException {
        byte[] utf8 = Files.readAllBytes(UTF8_SAMPLE);

        DecodedSource auto = decoder.decode(utf8);
        DecodedSource manual = decoder.decode(utf8, CodePage.UTF_8);

        assertEquals(auto.text(), manual.text());
        assertEquals(false, auto.encodingInfo().manualOverride());
        assertEquals(true, manual.encodingInfo().manualOverride());
    }
}
