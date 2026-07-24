package jp.cobolinsight.transpile;

import jp.cobolinsight.engineapi.picture.PictureType;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * COBOL データ項目の値とバイト列を相互変換する参照実装。後続の Python/Java アクセサ生成の意味仕様
 * かつ検証オラクルになる。数値は暗黙小数点(V)を持たない整数値(格納桁列そのもの)として扱う。
 *
 * <ul>
 *   <li>COMP-3(パック10進): 各ハーフバイトが1桁、末尾ハーフバイトが符号(signed は正=0xC/負=0xD、
 *       unsigned は 0xF)。バイト長は {@link PictureType#byteLength()}。
 *   <li>DISPLAY 数値(ゾーン10進, EBCDIC): 1バイト1桁で上位ニブルは既定 0xF、末尾桁に符号を
 *       オーバーパンチ(signed 正=0xC/負=0xD、unsigned=0xF)。
 *   <li>BINARY(COMP/COMP-4/COMP-5): ビッグエンディアン2の補数。unsigned 項目は非負のみ。
 *   <li>英数字(X): 指定バイト長へ空白(0x20)で右詰めパディング、超過は切り詰め。
 * </ul>
 */
public final class ValueCodec {

    private ValueCodec() {
    }

    // ---- COMP-3 パック10進 ----

    public static byte[] encodePacked(BigInteger value, PictureType type) {
        int byteLength = type.byteLength();
        int totalNibbles = byteLength * 2;
        int digitNibbles = totalNibbles - 1;
        String digits = value.abs().toString();
        if (digits.length() > digitNibbles) {
            throw new IllegalArgumentException(
                    "value " + value + " exceeds " + digitNibbles + " packed digits");
        }
        int[] nibbles = new int[totalNibbles];
        nibbles[totalNibbles - 1] = signNibble(type.signed(), value.signum());
        int pos = totalNibbles - 2;
        for (int i = digits.length() - 1; i >= 0; i--) {
            nibbles[pos--] = digits.charAt(i) - '0';
        }
        byte[] out = new byte[byteLength];
        for (int b = 0; b < byteLength; b++) {
            out[b] = (byte) ((nibbles[2 * b] << 4) | nibbles[2 * b + 1]);
        }
        return out;
    }

    public static BigInteger decodePacked(byte[] bytes) {
        if (bytes.length == 0) {
            throw new IllegalArgumentException("bytes must not be empty");
        }
        int totalNibbles = bytes.length * 2;
        StringBuilder digits = new StringBuilder();
        for (int n = 0; n < totalNibbles - 1; n++) {
            int nibble = nibbleAt(bytes, n);
            if (nibble > 9) {
                throw new IllegalArgumentException("invalid packed digit nibble: " + nibble);
            }
            digits.append((char) ('0' + nibble));
        }
        int sign = nibbleAt(bytes, totalNibbles - 1);
        BigInteger magnitude = new BigInteger(digits.toString());
        return sign == 0xD ? magnitude.negate() : magnitude;
    }

    // ---- DISPLAY ゾーン10進 ----

    public static byte[] encodeZoned(BigInteger value, PictureType type) {
        int length = type.byteLength();
        String digits = value.abs().toString();
        if (digits.length() > length) {
            throw new IllegalArgumentException(
                    "value " + value + " exceeds " + length + " zoned digits");
        }
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) 0xF0);
        int pos = length - 1;
        for (int i = digits.length() - 1; i >= 0; i--) {
            out[pos--] = (byte) (0xF0 | (digits.charAt(i) - '0'));
        }
        int lastDigit = out[length - 1] & 0x0F;
        out[length - 1] = (byte) ((signZone(type.signed(), value.signum()) << 4) | lastDigit);
        return out;
    }

    public static BigInteger decodeZoned(byte[] bytes) {
        if (bytes.length == 0) {
            throw new IllegalArgumentException("bytes must not be empty");
        }
        StringBuilder digits = new StringBuilder();
        for (byte b : bytes) {
            digits.append((char) ('0' + (b & 0x0F)));
        }
        int lastZone = (bytes[bytes.length - 1] & 0xF0) >> 4;
        BigInteger magnitude = new BigInteger(digits.toString());
        return lastZone == 0xD ? magnitude.negate() : magnitude;
    }

    // ---- BINARY ----

    public static byte[] encodeBinary(BigInteger value, PictureType type) {
        int width = type.byteLength();
        BigInteger min;
        BigInteger max;
        if (type.signed()) {
            max = BigInteger.ONE.shiftLeft(width * 8 - 1).subtract(BigInteger.ONE);
            min = BigInteger.ONE.shiftLeft(width * 8 - 1).negate();
        } else {
            max = BigInteger.ONE.shiftLeft(width * 8).subtract(BigInteger.ONE);
            min = BigInteger.ZERO;
        }
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(
                    "value " + value + " out of range for " + width + "-byte binary field");
        }
        BigInteger mask = BigInteger.ONE.shiftLeft(width * 8).subtract(BigInteger.ONE);
        BigInteger twosComplement = value.and(mask);
        byte[] out = new byte[width];
        for (int i = 0; i < width; i++) {
            out[width - 1 - i] = twosComplement.shiftRight(8 * i).and(BigInteger.valueOf(0xFF)).byteValue();
        }
        return out;
    }

    public static BigInteger decodeBinary(byte[] bytes, PictureType type) {
        if (bytes.length == 0) {
            throw new IllegalArgumentException("bytes must not be empty");
        }
        return type.signed() ? new BigInteger(bytes) : new BigInteger(1, bytes);
    }

    // ---- 英数字 ----

    public static byte[] encodeAlphanumeric(String value, int length, Charset charset) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0: " + length);
        }
        byte[] raw = value.getBytes(charset);
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) 0x20);
        System.arraycopy(raw, 0, out, 0, Math.min(raw.length, length));
        return out;
    }

    public static String decodeAlphanumeric(byte[] bytes, Charset charset) {
        return new String(bytes, charset);
    }

    private static int signNibble(boolean signed, int signum) {
        if (!signed) {
            return 0xF;
        }
        return signum < 0 ? 0xD : 0xC;
    }

    private static int signZone(boolean signed, int signum) {
        if (!signed) {
            return 0xF;
        }
        return signum < 0 ? 0xD : 0xC;
    }

    private static int nibbleAt(byte[] bytes, int nibbleIndex) {
        int b = bytes[nibbleIndex / 2] & 0xFF;
        return (nibbleIndex % 2 == 0) ? (b >> 4) : (b & 0x0F);
    }
}
