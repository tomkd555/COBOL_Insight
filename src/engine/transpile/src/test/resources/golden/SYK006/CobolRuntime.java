package cobolinsight.runtime;

import java.nio.charset.StandardCharsets;

/**
 * COBOL データ項目の値とバイト列を相互変換する実行時コーデック(自動生成・入力非依存)。
 * 符号規約: COMP-3 とゾーン10進は signed 正=0xC・負=0xD・unsigned=0xF、復号は末尾
 * ハーフバイト(またはゾーン)が 0xD のとき負とする。BINARY はビッグエンディアン2の補数。
 * 英数字は ISO-8859-1(1バイト1文字)で符号化し、不足は空白(0x20)で右詰めする。
 */
public final class CobolRuntime {

    private CobolRuntime() {
    }

    public static long decodePacked(byte[] data, int offset, int length) {
        int totalNibbles = length * 2;
        StringBuilder digits = new StringBuilder();
        for (int n = 0; n < totalNibbles - 1; n++) {
            digits.append((char) ('0' + nibble(data, offset, n)));
        }
        long magnitude = digits.length() == 0 ? 0L : Long.parseLong(digits.toString());
        return nibble(data, offset, totalNibbles - 1) == 0x0D ? -magnitude : magnitude;
    }

    public static void encodePacked(byte[] data, int offset, int length, boolean signed,
            long value) {
        int totalNibbles = length * 2;
        int[] nibbles = new int[totalNibbles];
        nibbles[totalNibbles - 1] = signed ? (value < 0 ? 0x0D : 0x0C) : 0x0F;
        String digits = Long.toString(Math.abs(value));
        int pos = totalNibbles - 2;
        for (int i = digits.length() - 1; i >= 0 && pos >= 0; i--) {
            nibbles[pos--] = digits.charAt(i) - '0';
        }
        for (int b = 0; b < length; b++) {
            data[offset + b] = (byte) ((nibbles[2 * b] << 4) | nibbles[2 * b + 1]);
        }
    }

    public static long decodeZoned(byte[] data, int offset, int length) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < length; i++) {
            digits.append((char) ('0' + (data[offset + i] & 0x0F)));
        }
        long magnitude = length == 0 ? 0L : Long.parseLong(digits.toString());
        int lastZone = (data[offset + length - 1] & 0xF0) >> 4;
        return lastZone == 0x0D ? -magnitude : magnitude;
    }

    public static void encodeZoned(byte[] data, int offset, int length, boolean signed,
            long value) {
        for (int i = 0; i < length; i++) {
            data[offset + i] = (byte) 0xF0;
        }
        String digits = Long.toString(Math.abs(value));
        int pos = length - 1;
        for (int i = digits.length() - 1; i >= 0 && pos >= 0; i--) {
            data[offset + pos--] = (byte) (0xF0 | (digits.charAt(i) - '0'));
        }
        int sign = signed ? (value < 0 ? 0x0D : 0x0C) : 0x0F;
        int lastDigit = data[offset + length - 1] & 0x0F;
        data[offset + length - 1] = (byte) ((sign << 4) | lastDigit);
    }

    public static long decodeBinary(byte[] data, int offset, int length, boolean signed) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        if (signed && length < 8) {
            long signBit = 1L << (length * 8 - 1);
            if ((value & signBit) != 0) {
                value -= (1L << (length * 8));
            }
        }
        return value;
    }

    public static void encodeBinary(byte[] data, int offset, int length, boolean signed,
            long value) {
        long v = value;
        for (int i = length - 1; i >= 0; i--) {
            data[offset + i] = (byte) (v & 0xFF);
            v >>= 8;
        }
    }

    public static String decodeAlphanumeric(byte[] data, int offset, int length) {
        return new String(data, offset, length, StandardCharsets.ISO_8859_1);
    }

    public static void encodeAlphanumeric(byte[] data, int offset, int length,
            String value) {
        byte[] raw = value.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < length; i++) {
            data[offset + i] = i < raw.length ? raw[i] : (byte) 0x20;
        }
    }

    private static int nibble(byte[] data, int offset, int nibbleIndex) {
        int b = data[offset + nibbleIndex / 2] & 0xFF;
        return (nibbleIndex % 2 == 0) ? (b >> 4) : (b & 0x0F);
    }
}
