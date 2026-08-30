package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.json.JsonReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code decode}: the source viewer's only way to learn where the fixed-format areas start.
 *
 * <p>The areas are separated by <em>byte</em> columns, so on a line holding double-byte characters
 * the boundary is not the column number counted in characters. Working that out is the engine's
 * job, because only it holds the byte-to-character table; the viewer must be able to take the
 * indexes as they are.
 */
class DecodeCommandTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples", "encoding")
            .toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    private Map<String, Object> decode(String fileName, String... extraArgs) {
        Path out = tempDir.resolve(fileName + ".json");
        List<String> args = new ArrayList<>(List.of("decode",
                "--file", SAMPLES.resolve(fileName).toString()));
        args.addAll(List.of(extraArgs));
        args.addAll(List.of("--out", out.toString()));

        assertEquals(0, new CommandLine(new Main()).execute(args.toArray(String[]::new)), fileName);
        return readJson(out);
    }

    @ParameterizedTest
    @CsvSource({
        "SYKENC1_UTF8.cbl, UTF-8",
        "SYKENC1_SJIS.cbl, windows-31j",
    })
    void detectedSamplesDecodeWithTheirOwnCodePage(String fileName, String codePage) {
        Map<String, Object> result = decode(fileName);
        assertEquals(codePage, result.get("codepage"));
        assertEquals(Boolean.TRUE, result.get("detected"));
        assertEquals("", result.get("error"));
        assertTrue(((String) result.get("text")).contains("PROGRAM-ID"));
        assertEquals(26, JsonReader.asArray(result.get("lines")).size());
    }

    /** The EBCDIC samples carry no signature the estimator can read, so they take an override. */
    @ParameterizedTest
    @CsvSource({
        "SYKENC1_CP930.cbl, cp930, x-IBM930",
        "SYKENC1_CP939.cbl, cp939, x-IBM939",
    })
    void ebcdicSamplesDecodeUnderAnOverride(String fileName, String requested, String reported) {
        Map<String, Object> result = decode(fileName, "--codepage", requested);
        assertEquals(reported, result.get("codepage"));
        assertEquals(Boolean.FALSE, result.get("detected"));
        assertEquals(Boolean.TRUE, result.get("soSiPresent"),
                "the double-byte runs of an EBCDIC source are wrapped in SO/SI");
        assertTrue(((String) result.get("text")).contains("PROGRAM-ID"));
    }

    /**
     * Line 3 of the Shift_JIS sample, hand-counted:
     *
     * <pre>
     *   char index  0..5   six spaces          bytes 0..5
     *   char index  6      '*'                 byte  6
     *   char index  7,8    two spaces          bytes 7,8
     *   char index  9..25  17 double-byte      bytes 9..42
     *   char index 26..54  29 spaces           bytes 43..71
     *   char index 55      '*'                 byte  72
     * </pre>
     *
     * <p>So byte column 7 is char 6, column 8 is char 7, column 12 falls inside the second
     * double-byte character and therefore reports its start at char 10, and column 73 is char 55 —
     * not char 72, which is what counting characters would have given.
     */
    @Test
    void byteColumnsOfADoubleByteLineAreNotItsCharacterColumns() {
        Map<String, Object> result = decode("SYKENC1_SJIS.cbl");
        Map<String, Object> line = JsonReader.asObject(
                JsonReader.asArray(result.get("lines")).get(2));

        assertEquals(74L, ((Number) line.get("byteLength")).longValue(),
                "73 bytes of text plus the line terminator");
        assertEquals(List.of(6L, 7L, 10L, 55L), JsonReader.asArray(line.get("boundaries")).stream()
                .map(value -> ((Number) value).longValue()).toList());
    }

    /** A line that never reaches a boundary column reports -1 for it, not a clamped index. */
    @Test
    void shortLinesReportMinusOneForTheColumnsTheyDoNotReach() {
        Map<String, Object> result = decode("SYKENC1_SJIS.cbl");
        Map<String, Object> line = JsonReader.asObject(
                JsonReader.asArray(result.get("lines")).get(15));

        assertEquals(List.of(6L, 7L, 11L, -1L), JsonReader.asArray(line.get("boundaries")).stream()
                .map(value -> ((Number) value).longValue()).toList());
    }

    @Test
    void anUnreadableFileIsReportedInTheJsonRatherThanOnStandardError() {
        Path out = tempDir.resolve("missing.json");
        int exitCode = new CommandLine(new Main()).execute("decode",
                "--file", tempDir.resolve("absent.cbl").toString(), "--out", out.toString());

        assertEquals(2, exitCode);
        Map<String, Object> result = readJson(out);
        assertFalse(((String) result.get("error")).isBlank());
        assertEquals("", result.get("text"));
    }

    private static Map<String, Object> readJson(Path path) {
        try {
            return JsonReader.asObject(
                    JsonReader.parse(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
