package jp.cobolinsight.fix;

import jp.cobolinsight.encoding.CodePage;
import jp.cobolinsight.encoding.DecodedSource;
import jp.cobolinsight.encoding.SourceDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 往復忠実性ゴールデン。原本を復号し無編集リライト(空編集のスプライス適用)した出力バイト列が、
 * 原本バイト列とバイト単位で一致することを検証する。Shift_JIS 経路は再エンコードで最も壊れやすく、
 * 独立した検証を置く。
 */
class RoundTripFidelityGoldenTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples");

    private final SourceDecoder decoder = new SourceDecoder();
    private final ByteSpliceApplier applier = new ByteSpliceApplier();

    static List<Path> goldenFiles() {
        List<Path> files = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            files.add(SAMPLES.resolve("cobol").resolve(String.format("SYK%03d.cbl", i)));
        }
        for (int i = 1; i <= 3; i++) {
            files.add(SAMPLES.resolve("copybook").resolve("SYKCPY" + i + ".cpy"));
        }
        files.add(SAMPLES.resolve("encoding").resolve("SYKENC1_UTF8.cbl"));
        files.add(SAMPLES.resolve("encoding").resolve("SYKENC1_SJIS.cbl"));
        return files;
    }

    @Test
    void goldenSetHasFourteenFiles() {
        assertEquals(14, goldenFiles().size());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenFiles")
    void noEditRewriteReproducesOriginalBytes(Path file) throws IOException {
        byte[] original = Files.readAllBytes(file);
        DecodedSource decoded = decoder.decode(original);
        byte[] rewritten = applier.apply(decoded, List.of());
        assertArrayEquals(original, rewritten, file + " の無編集リライトは原本とバイト一致する");
    }

    @Test
    void shiftJisSampleIsDetectedAndRoundTripsByteForByte() throws IOException {
        Path file = SAMPLES.resolve("encoding").resolve("SYKENC1_SJIS.cbl");
        byte[] original = Files.readAllBytes(file);
        assertEquals(1_124, original.length, "SYKENC1_SJIS.cbl は1124バイト");

        DecodedSource decoded = decoder.decode(original);
        assertEquals(CodePage.SHIFT_JIS, decoded.encodingInfo().codePage(), "Shift_JIS として判別する");
        assertEquals(993, decoded.text().length(), "復号後は993文字");

        byte[] rewritten = applier.apply(decoded, List.of());
        assertArrayEquals(original, rewritten, "Shift_JIS 経路の無編集リライトは原本とバイト一致する");
    }
}
