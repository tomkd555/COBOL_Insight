import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Encodes a UTF-8 COBOL source into its EBCDIC renditions, one file per code page, named
 * {@code <stem>_<CP>.cbl} next to the source or in the given directory.
 * Run from the repository root:
 *   java tools/GenerateEbcdicSamples.java
 *       -> samples/encoding/SYKENC1_CP930.cbl and SYKENC1_CP939.cbl
 *   java tools/GenerateEbcdicSamples.java samples-field/encoding/FLENC1_UTF8.cbl samples-field/encoding CP930
 *       -> samples-field/encoding/FLENC1_CP930.cbl (IBM939 has no half-width katakana)
 * Line separators become EBCDIC LF (0x25); unencodable characters abort the run.
 */
public final class GenerateEbcdicSamples {
    public static void main(String[] args) throws Exception {
        Path source = args.length > 0 ? Path.of(args[0])
                : Path.of("samples", "encoding", "SYKENC1_UTF8.cbl");
        Path dir = args.length > 1 ? Path.of(args[1]) : source.getParent();
        List<String> codePages = args.length > 2 ? Arrays.asList(args).subList(2, args.length)
                : List.of("CP930", "CP939");
        String stem = source.getFileName().toString().replaceFirst("_UTF8\\.cbl$", "");
        String text = Files.readString(source).replace("\r\n", "\n");
        for (String cp : codePages) {
            CharsetEncoder enc = Charset.forName("x-IBM" + cp.substring(2)).newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            var buf = enc.encode(java.nio.CharBuffer.wrap(text));
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            Path out = dir.resolve(stem + "_" + cp + ".cbl");
            Files.write(out, bytes);
            System.out.println(out + ": " + bytes.length + " bytes");
        }
    }
}
