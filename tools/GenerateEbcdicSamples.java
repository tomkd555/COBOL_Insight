import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates samples/encoding/SYKENC1_CP930.cbl and SYKENC1_CP939.cbl from SYKENC1_UTF8.cbl.
 * Run from the repository root: java tools/GenerateEbcdicSamples.java
 * Line separators become EBCDIC LF (0x25); unencodable characters abort the run.
 */
public final class GenerateEbcdicSamples {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of("samples", "encoding");
        String text = Files.readString(dir.resolve("SYKENC1_UTF8.cbl")).replace("\r\n", "\n");
        for (String[] cp : new String[][] {{"x-IBM930", "CP930"}, {"x-IBM939", "CP939"}}) {
            CharsetEncoder enc = Charset.forName(cp[0]).newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            var buf = enc.encode(java.nio.CharBuffer.wrap(text));
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            Path out = dir.resolve("SYKENC1_" + cp[1] + ".cbl");
            Files.write(out, bytes);
            System.out.println(out + ": " + bytes.length + " bytes");
        }
    }
}
