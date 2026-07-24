package jp.cobolinsight.transpile;

import jp.cobolinsight.engineapi.picture.PictureType;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 値コーデックの既知バイト列一致と encode→decode 往復の検証。 */
class ValueCodecTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static BigInteger big(long v) {
        return BigInteger.valueOf(v);
    }

    // ---- COMP-3 パック10進 ----

    @Test
    void packedSignedPositive123MatchesKnownBytes() {
        PictureType type = PictureType.parse("S9(3)", "COMP-3");
        assertArrayEquals(bytes(0x12, 0x3C), ValueCodec.encodePacked(big(123), type));
    }

    @Test
    void packedSignedNegative123UsesDSignNibble() {
        PictureType type = PictureType.parse("S9(3)", "COMP-3");
        assertArrayEquals(bytes(0x12, 0x3D), ValueCodec.encodePacked(big(-123), type));
    }

    @Test
    void packedUnsigned123UsesFSignNibble() {
        PictureType type = PictureType.parse("9(3)", "COMP-3");
        assertArrayEquals(bytes(0x12, 0x3F), ValueCodec.encodePacked(big(123), type));
    }

    @Test
    void packedElevenDigitFieldSpansSixBytes() {
        PictureType type = PictureType.parse("S9(09)V99", "COMP-3");
        assertEquals(6, type.byteLength());
        assertArrayEquals(bytes(0x12, 0x34, 0x56, 0x78, 0x90, 0x1C),
                ValueCodec.encodePacked(big(12345678901L), type));
    }

    @Test
    void packedRoundTripsPositiveNegativeAndZero() {
        PictureType type = PictureType.parse("S9(09)V99", "COMP-3");
        for (long v : new long[]{0, 1, -1, 12345678901L, -98765432109L}) {
            byte[] encoded = ValueCodec.encodePacked(big(v), type);
            assertEquals(big(v), ValueCodec.decodePacked(encoded), "packed round-trip " + v);
        }
    }

    @Test
    void packedRejectsTooManyDigits() {
        PictureType type = PictureType.parse("S9(3)", "COMP-3");
        assertThrows(IllegalArgumentException.class, () -> ValueCodec.encodePacked(big(12345), type));
    }

    // ---- DISPLAY ゾーン10進 ----

    @Test
    void zonedSignedPositiveUsesCZoneOnTrailingByte() {
        PictureType type = PictureType.parse("S9(3)", null);
        assertArrayEquals(bytes(0xF1, 0xF2, 0xC3), ValueCodec.encodeZoned(big(123), type));
    }

    @Test
    void zonedSignedNegativeUsesDZoneOnTrailingByte() {
        PictureType type = PictureType.parse("S9(3)", null);
        assertArrayEquals(bytes(0xF1, 0xF2, 0xD3), ValueCodec.encodeZoned(big(-123), type));
    }

    @Test
    void zonedUnsignedKeepsFZone() {
        PictureType type = PictureType.parse("9(3)", null);
        assertArrayEquals(bytes(0xF1, 0xF2, 0xF3), ValueCodec.encodeZoned(big(123), type));
    }

    @Test
    void zonedRoundTripsWithLeadingZeroPadding() {
        PictureType type = PictureType.parse("S9(5)", null);
        for (long v : new long[]{0, 7, -7, 42, -99999}) {
            byte[] encoded = ValueCodec.encodeZoned(big(v), type);
            assertEquals(5, encoded.length);
            assertEquals(big(v), ValueCodec.decodeZoned(encoded), "zoned round-trip " + v);
        }
    }

    // ---- BINARY ----

    @Test
    void binaryUnsignedTwoBytesBigEndian() {
        PictureType type = PictureType.parse("9(4)", "COMP");
        assertEquals(2, type.byteLength());
        assertArrayEquals(bytes(0x01, 0x02), ValueCodec.encodeBinary(big(258), type));
        assertEquals(big(258), ValueCodec.decodeBinary(bytes(0x01, 0x02), type));
    }

    @Test
    void binarySignedNegativeIsTwosComplement() {
        PictureType type = PictureType.parse("S9(4)", "COMP");
        assertArrayEquals(bytes(0xFF, 0xFF), ValueCodec.encodeBinary(big(-1), type));
        assertEquals(big(-1), ValueCodec.decodeBinary(bytes(0xFF, 0xFF), type));
    }

    @Test
    void binaryRoundTripsAcrossWidths() {
        PictureType signed = PictureType.parse("S9(9)", "COMP");
        assertEquals(4, signed.byteLength());
        for (long v : new long[]{0, 1, -1, 123456789, -123456789}) {
            assertEquals(big(v), ValueCodec.decodeBinary(ValueCodec.encodeBinary(big(v), signed), signed),
                    "binary round-trip " + v);
        }
    }

    @Test
    void binaryRejectsNegativeForUnsignedField() {
        PictureType type = PictureType.parse("9(4)", "COMP");
        assertThrows(IllegalArgumentException.class, () -> ValueCodec.encodeBinary(big(-1), type));
    }

    @Test
    void binaryRejectsOutOfRange() {
        PictureType type = PictureType.parse("9(4)", "COMP");
        assertThrows(IllegalArgumentException.class, () -> ValueCodec.encodeBinary(big(70000), type));
    }

    // ---- 英数字 ----

    @Test
    void alphanumericPadsWithSpacesToLength() {
        assertArrayEquals(bytes(0x41, 0x42, 0x43, 0x20, 0x20),
                ValueCodec.encodeAlphanumeric("ABC", 5, StandardCharsets.US_ASCII));
    }

    @Test
    void alphanumericTruncatesToLength() {
        assertArrayEquals(bytes(0x41, 0x42),
                ValueCodec.encodeAlphanumeric("ABCDE", 2, StandardCharsets.US_ASCII));
    }

    @Test
    void alphanumericRoundTripsFullWidthValue() {
        byte[] encoded = ValueCodec.encodeAlphanumeric("HELLO", 5, StandardCharsets.US_ASCII);
        assertEquals("HELLO", ValueCodec.decodeAlphanumeric(encoded, StandardCharsets.US_ASCII));
    }
}
