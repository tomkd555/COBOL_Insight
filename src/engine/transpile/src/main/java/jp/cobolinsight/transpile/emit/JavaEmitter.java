package jp.cobolinsight.transpile.emit;

import jp.cobolinsight.core.transpile.GeneratedFile;
import jp.cobolinsight.core.transpile.TargetLanguage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Java 生成のレンダリング。byte[] を裏に持つクラスと、ランタイムヘルパ CobolRuntime.java を出力する。 */
public final class JavaEmitter implements LanguageEmitter {

    private static final String RUNTIME_FILE_NAME = "CobolRuntime.java";

    private static final String RUNTIME_SOURCE =
            """
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
            """;

    @Override
    public TargetLanguage language() {
        return TargetLanguage.JAVA;
    }

    @Override
    public String indentUnit() {
        return "    ";
    }

    @Override
    public String recordFileName(String recordCobolName) {
        return Identifiers.sanitize(recordCobolName) + ".java";
    }

    @Override
    public String runtimeFileName() {
        return RUNTIME_FILE_NAME;
    }

    @Override
    public GeneratedFile runtimeLibrary() {
        return new GeneratedFile(RUNTIME_FILE_NAME, RUNTIME_SOURCE);
    }

    @Override
    public void emitFileHeader(LineTrackingEmitter out, String programId, String recordCobolName) {
        out.emit("package cobolinsight.generated;");
        out.blank();
        out.emit("import cobolinsight.runtime.CobolRuntime;");
        out.emit("import java.util.Arrays;");
        out.blank();
        out.emit("/** " + programId + " のデータ部レコード " + recordCobolName + " を逐語対訳した自動生成コード。 */");
    }

    @Override
    public void emitClassHeader(LineTrackingEmitter out, String className, String cobolName,
            int totalBytes) {
        out.emit("public final class " + className + " {");
        out.indent();
        out.blank();
        out.emit("/** 01 " + cobolName + " 総バイト長 " + totalBytes + "。 */");
        out.emit("public static final int LENGTH = " + totalBytes + ";");
        out.blank();
        out.emit("private final byte[] data;");
        out.blank();
        out.emit("public " + className + "() {");
        out.indent();
        out.emit("this.data = new byte[" + totalBytes + "];");
        out.dedent();
        out.emit("}");
        out.blank();
        out.emit("public " + className + "(byte[] source) {");
        out.indent();
        out.emit("this.data = Arrays.copyOf(source, " + totalBytes + ");");
        out.dedent();
        out.emit("}");
        out.blank();
        out.emit("public byte[] data() {");
        out.indent();
        out.emit("return data;");
        out.dedent();
        out.emit("}");
    }

    @Override
    public void emitClassFooter(LineTrackingEmitter out) {
        out.dedent();
        out.emit("}");
    }

    @Override
    public void emitGroupComment(LineTrackingEmitter out, String cobolName, int offset,
            int byteLength, Optional<Integer> occursCount, Optional<String> redefinesTarget) {
        if (redefinesTarget.isPresent()) {
            out.emit("// " + cobolName + " REDEFINES " + redefinesTarget.get()
                    + " (同一オフセット " + offset + ")");
        } else if (occursCount.isPresent()) {
            out.emit("// " + cobolName + " OCCURS " + occursCount.get() + " TIMES (要素長 "
                    + byteLength + ", offset " + offset + ")");
        } else {
            out.emit("// " + cobolName + " (offset " + offset + ", 長さ " + byteLength + ")");
        }
    }

    @Override
    public void emitAccessor(LineTrackingEmitter out, AccessorSpec spec) {
        out.emit("// " + spec.cobolName());
        String type = javaType(spec.kind());
        List<String> getParams = new ArrayList<>();
        for (String p : spec.indexParams()) {
            getParams.add("int " + p);
        }
        out.emit("public " + type + " get_" + spec.memberName() + "("
                + String.join(", ", getParams) + ") {");
        out.indent();
        out.emit("return " + decodeCall(spec) + ";");
        out.dedent();
        out.emit("}");
        List<String> setParams = new ArrayList<>(getParams);
        setParams.add(type + " value");
        out.emit("public void set_" + spec.memberName() + "("
                + String.join(", ", setParams) + ") {");
        out.indent();
        out.emit(encodeCall(spec) + ";");
        out.dedent();
        out.emit("}");
    }

    @Override
    public void emitConditionPredicate(LineTrackingEmitter out, ConditionSpec spec) {
        List<String> params = new ArrayList<>();
        for (String p : spec.indexParams()) {
            params.add("int " + p);
        }
        out.emit("public boolean is_" + spec.memberName() + "("
                + String.join(", ", params) + ") {");
        out.indent();
        String call = spec.parentGetter() + "(" + String.join(", ", spec.indexParams()) + ")";
        List<String> conditions = new ArrayList<>();
        for (String value : spec.values()) {
            conditions.add(condition(call, spec.parentKind(), value));
        }
        out.emit("return " + String.join(" || ", conditions) + ";");
        out.dedent();
        out.emit("}");
    }

    private static String decodeCall(AccessorSpec spec) {
        String off = spec.offsetExpression();
        int len = spec.byteLength();
        return switch (spec.kind()) {
            case PACKED -> "CobolRuntime.decodePacked(data, " + off + ", " + len + ")";
            case ZONED -> "CobolRuntime.decodeZoned(data, " + off + ", " + len + ")";
            case BINARY -> "CobolRuntime.decodeBinary(data, " + off + ", " + len + ", "
                    + javaBool(spec.signed()) + ")";
            case ALPHANUMERIC -> "CobolRuntime.decodeAlphanumeric(data, " + off + ", " + len + ")";
        };
    }

    private static String encodeCall(AccessorSpec spec) {
        String off = spec.offsetExpression();
        int len = spec.byteLength();
        return switch (spec.kind()) {
            case PACKED -> "CobolRuntime.encodePacked(data, " + off + ", " + len + ", "
                    + javaBool(spec.signed()) + ", value)";
            case ZONED -> "CobolRuntime.encodeZoned(data, " + off + ", " + len + ", "
                    + javaBool(spec.signed()) + ", value)";
            case BINARY -> "CobolRuntime.encodeBinary(data, " + off + ", " + len + ", "
                    + javaBool(spec.signed()) + ", value)";
            case ALPHANUMERIC -> "CobolRuntime.encodeAlphanumeric(data, " + off + ", " + len
                    + ", value)";
        };
    }

    /**
     * 88レベル VALUE の1件を親項目との比較式へ写す。VALUE が引用符付きなら親が数値項目でも文字列として
     * 比較し、原文の表記に従う。{@code low THRU high} は下限・上限の範囲比較へ展開する。表意定数は
     * 値へ写し、文字コード系に依存して写せないものは常に偽の式と注記へ落とす。
     */
    private static String condition(String call, FieldKind parentKind, String value) {
        if (Literals.isCodePageDependentFigurative(value)) {
            return "false /* " + value.trim() + " は文字コード系に依存するため対訳しない */";
        }
        boolean asString = parentKind == FieldKind.ALPHANUMERIC || Literals.isQuoted(value);
        int thru = Literals.indexOfThru(value);
        if (thru >= 0) {
            String lo = value.substring(0, thru).trim();
            String hi = value.substring(thru + Literals.thruLength()).trim();
            if (asString) {
                return "(" + call + ".compareTo(" + strLiteral(lo) + ") >= 0 && " + call
                        + ".compareTo(" + strLiteral(hi) + ") <= 0)";
            }
            return "(" + call + " >= " + num(lo) + " && " + call + " <= " + num(hi) + ")";
        }
        if (asString) {
            return call + ".equals(" + strLiteral(value) + ")";
        }
        return call + " == " + num(value);
    }

    private static String strLiteral(String value) {
        return "\"" + Literals.resolve(value) + "\"";
    }

    private static String num(String value) {
        return Literals.resolve(value);
    }

    private static String javaType(FieldKind kind) {
        return kind == FieldKind.ALPHANUMERIC ? "String" : "long";
    }

    private static String javaBool(boolean value) {
        return value ? "true" : "false";
    }
}
