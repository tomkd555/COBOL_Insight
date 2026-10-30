package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The bytes the parse cache keeps a result in, and what decoding refuses to read back. */
class RecordCodecTest {

    /** A record whose components take every tag the codec writes. */
    private record EveryTag(Object nothing, String text, String sameText, int number, long large,
            boolean flag, double fraction, AssetKind kind, Optional<String> absent,
            Optional<String> present, List<Object> list, Set<String> set,
            Map<String, Integer> map, SourcePosition nested) {
    }

    @Test
    void everyTagComesBackAsItWasWritten() throws IOException {
        Set<String> set = new LinkedHashSet<>(List.of("B", "A"));
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("Z", 1);
        map.put("Y", 2);
        EveryTag value = new EveryTag(null, "TEXT", "TEXT", -7, Long.MAX_VALUE, true, 0.5,
                AssetKind.COPYBOOK, Optional.empty(), Optional.of("SOME"),
                List.of("TEXT", 3, List.of()), set, map,
                new SourcePosition("PGM.cbl", 3, 8, 120));

        Object decoded = RecordCodec.decode(RecordCodec.encode(value));

        assertEquals(value, decoded);
        EveryTag back = (EveryTag) decoded;
        assertEquals(List.of("B", "A"), List.copyOf(back.set()), "a set keeps its order");
        assertEquals(List.of("Z", "Y"), List.copyOf(back.map().keySet()), "a map keeps its order");
    }

    @Test
    void aValueOfAnotherTypeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> RecordCodec.encode(new Object()));
        assertThrows(IllegalArgumentException.class,
                () -> RecordCodec.encode(List.of(UUID.randomUUID())));
    }

    @Test
    void aClassOutsideTheEngineIsNeverLoaded() throws IOException {
        assertThrows(IOException.class,
                () -> RecordCodec.decode(named(13, "java.lang.ProcessBuilder")));
        assertThrows(IOException.class,
                () -> RecordCodec.decode(named(7, "java.lang.ProcessBuilder")));
        // A record and an enum the bytes would rebuild but for the package they stand in.
        assertThrows(IOException.class, () -> RecordCodec.decode(
                named(13, org.example.outside.OutsideRecord.class.getName())));
        assertThrows(IOException.class, () -> RecordCodec.decode(
                named(7, org.example.outside.OutsideRecord.Kind.class.getName())));
    }

    /** Values nested deeper than the codec reads are refused before the stack runs out. */
    @Test
    void valuesNestedTooDeepAreRefused() {
        byte[] optionals = new byte[2_000_000];
        java.util.Arrays.fill(optionals, (byte) 0x09);
        assertThrows(IOException.class, () -> RecordCodec.decode(optionals));
    }

    /** A list read back with elements of another type than its component declares is refused. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aListOfTheWrongElementTypeIsRefused() {
        jp.cobolinsight.core.semantic.CobolSemanticModel model =
                new jp.cobolinsight.core.semantic.CobolSemanticModel("PGM", "PGM.cbl", List.of(),
                        (List) List.of("NOT A PROCEDURE"), List.of(), List.of(), List.of(),
                        List.of(), List.of());
        assertThrows(IOException.class, () -> RecordCodec.decode(RecordCodec.encode(model)));
    }

    @Test
    void anEngineClassThatIsNeitherRecordNorEnumIsRefused() throws IOException {
        assertThrows(IOException.class,
                () -> RecordCodec.decode(named(13, RecordCodec.class.getName())));
        assertThrows(IOException.class,
                () -> RecordCodec.decode(named(7, RecordCodec.class.getName())));
    }

    /** A count or a length the bytes do not back reads as damaged bytes, and sizes nothing. */
    @Test
    void aCountTheBytesDoNotBackIsRefused() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(10);
        out.writeInt(Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> RecordCodec.decode(bytes.toByteArray()));

        ByteArrayOutputStream string = new ByteArrayOutputStream();
        DataOutputStream text = new DataOutputStream(string);
        text.writeByte(1);
        text.writeInt(Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> RecordCodec.decode(string.toByteArray()));
    }

    /** The bytes of a value of tag {@code tag} whose first string is {@code name}. */
    private static byte[] named(int tag, String name) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(tag);
        byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
        out.writeByte(1);
        out.writeInt(utf8.length);
        out.write(utf8);
        out.writeByte(1);
        out.writeInt(1);
        out.write('A');
        return bytes.toByteArray();
    }
}
