package jp.cobolinsight.transpile.emit;

import jp.cobolinsight.core.transpile.GeneratedFile;
import jp.cobolinsight.core.transpile.TargetLanguage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Rendering for Python generation. Emits a class backed by a bytearray and the runtime helper cobol_runtime.py. */
public final class PythonEmitter implements LanguageEmitter {

    private static final String RUNTIME_FILE_NAME = "cobol_runtime.py";

    private static final String RUNTIME_SOURCE =
            """
            # COBOL データ項目の値とバイト列を相互変換する実行時コーデック(自動生成・入力非依存)。
            # 符号規約: COMP-3 とゾーン10進は signed 正=0xC・負=0xD・unsigned=0xF、復号は末尾
            # ハーフバイト(またはゾーン)が 0xD のとき負とする。BINARY はビッグエンディアン2の補数。
            # 英数字は ISO-8859-1(1バイト1文字)で符号化し、不足は空白(0x20)で右詰めする。


            def _nibble(data, offset, index):
                byte = data[offset + index // 2]
                return (byte >> 4) if index % 2 == 0 else (byte & 0x0F)


            def decode_packed(data, offset, length):
                total_nibbles = length * 2
                digits = "".join(str(_nibble(data, offset, n)) for n in range(total_nibbles - 1))
                magnitude = int(digits) if digits else 0
                return -magnitude if _nibble(data, offset, total_nibbles - 1) == 0x0D else magnitude


            def encode_packed(data, offset, length, signed, value):
                total_nibbles = length * 2
                nibbles = [0] * total_nibbles
                nibbles[-1] = (0x0D if value < 0 else 0x0C) if signed else 0x0F
                pos = total_nibbles - 2
                for ch in reversed(str(abs(value))):
                    if pos < 0:
                        break
                    nibbles[pos] = ord(ch) - ord("0")
                    pos -= 1
                for b in range(length):
                    data[offset + b] = (nibbles[2 * b] << 4) | nibbles[2 * b + 1]


            def decode_zoned(data, offset, length):
                digits = "".join(str(data[offset + i] & 0x0F) for i in range(length))
                magnitude = int(digits) if digits else 0
                last_zone = (data[offset + length - 1] & 0xF0) >> 4
                return -magnitude if last_zone == 0x0D else magnitude


            def encode_zoned(data, offset, length, signed, value):
                for i in range(length):
                    data[offset + i] = 0xF0
                pos = length - 1
                for ch in reversed(str(abs(value))):
                    if pos < 0:
                        break
                    data[offset + pos] = 0xF0 | (ord(ch) - ord("0"))
                    pos -= 1
                sign = (0x0D if value < 0 else 0x0C) if signed else 0x0F
                last_digit = data[offset + length - 1] & 0x0F
                data[offset + length - 1] = (sign << 4) | last_digit


            def decode_binary(data, offset, length, signed):
                return int.from_bytes(bytes(data[offset:offset + length]), "big", signed=signed)


            def encode_binary(data, offset, length, signed, value):
                data[offset:offset + length] = int(value).to_bytes(length, "big", signed=signed)


            def decode_alphanumeric(data, offset, length):
                return bytes(data[offset:offset + length]).decode("latin-1")


            def encode_alphanumeric(data, offset, length, value):
                raw = value.encode("latin-1")
                for i in range(length):
                    data[offset + i] = raw[i] if i < len(raw) else 0x20
            """;

    @Override
    public TargetLanguage language() {
        return TargetLanguage.PYTHON;
    }

    @Override
    public String indentUnit() {
        return "    ";
    }

    @Override
    public String recordFileName(String recordCobolName) {
        return Identifiers.sanitize(recordCobolName) + ".py";
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
        out.emit("\"\"\"" + programId + " のデータ部レコード " + recordCobolName
                + " を逐語対訳した自動生成コード。\"\"\"");
        out.emit("from cobol_runtime import (");
        out.indent();
        out.emit("decode_alphanumeric,");
        out.emit("decode_binary,");
        out.emit("decode_packed,");
        out.emit("decode_zoned,");
        out.emit("encode_alphanumeric,");
        out.emit("encode_binary,");
        out.emit("encode_packed,");
        out.emit("encode_zoned,");
        out.dedent();
        out.emit(")");
        out.blank();
        out.blank();
    }

    @Override
    public void emitClassHeader(LineTrackingEmitter out, String className, String cobolName,
            int totalBytes) {
        out.emit("class " + className + ":");
        out.indent();
        out.emit("\"\"\"01 " + cobolName + " 総バイト長 " + totalBytes + "。\"\"\"");
        out.blank();
        out.emit("LENGTH = " + totalBytes);
        out.blank();
        out.emit("def __init__(self, data=None):");
        out.indent();
        out.emit("if data is None:");
        out.indent();
        out.emit("self._data = bytearray(" + totalBytes + ")");
        out.dedent();
        out.emit("else:");
        out.indent();
        out.emit("self._data = bytearray(data)");
        out.emit("if len(self._data) < " + totalBytes + ":");
        out.indent();
        out.emit("self._data.extend(b\"\\x00\" * (" + totalBytes + " - len(self._data)))");
        out.dedent();
        out.dedent();
        out.dedent();
        out.blank();
        out.emit("@property");
        out.emit("def data(self):");
        out.indent();
        out.emit("return self._data");
        out.dedent();
    }

    @Override
    public void emitClassFooter(LineTrackingEmitter out) {
        // Since Python expresses blocks through indentation, there is no explicit closing.
    }

    @Override
    public void emitGroupComment(LineTrackingEmitter out, String cobolName, int offset,
            int byteLength, Optional<Integer> occursCount, Optional<String> redefinesTarget) {
        if (redefinesTarget.isPresent()) {
            out.emit("# " + cobolName + " REDEFINES " + redefinesTarget.get()
                    + " (同一オフセット " + offset + ")");
        } else if (occursCount.isPresent()) {
            out.emit("# " + cobolName + " OCCURS " + occursCount.get() + " TIMES (要素長 "
                    + byteLength + ", offset " + offset + ")");
        } else {
            out.emit("# " + cobolName + " (offset " + offset + ", 長さ " + byteLength + ")");
        }
    }

    @Override
    public void emitAccessor(LineTrackingEmitter out, AccessorSpec spec) {
        out.emit("# " + spec.cobolName());
        List<String> getParams = new ArrayList<>();
        getParams.add("self");
        getParams.addAll(spec.indexParams());
        out.emit("def get_" + spec.memberName() + "(" + String.join(", ", getParams) + "):");
        out.indent();
        out.emit("return " + decodeCall(spec));
        out.dedent();
        List<String> setParams = new ArrayList<>(getParams);
        setParams.add("value");
        out.emit("def set_" + spec.memberName() + "(" + String.join(", ", setParams) + "):");
        out.indent();
        out.emit(encodeCall(spec));
        out.dedent();
    }

    @Override
    public void emitConditionPredicate(LineTrackingEmitter out, ConditionSpec spec) {
        List<String> params = new ArrayList<>();
        params.add("self");
        params.addAll(spec.indexParams());
        out.emit("def is_" + spec.memberName() + "(" + String.join(", ", params) + "):");
        out.indent();
        String call = "self." + spec.parentGetter() + "(" + String.join(", ", spec.indexParams()) + ")";
        List<String> conditions = new ArrayList<>();
        for (String value : spec.values()) {
            conditions.add(condition(call, spec.parentKind(), value));
        }
        out.emit("return " + String.join(" or ", conditions));
        out.dedent();
    }

    private static String decodeCall(AccessorSpec spec) {
        String off = spec.offsetExpression();
        int len = spec.byteLength();
        return switch (spec.kind()) {
            case PACKED -> "decode_packed(self._data, " + off + ", " + len + ")";
            case ZONED -> "decode_zoned(self._data, " + off + ", " + len + ")";
            case BINARY -> "decode_binary(self._data, " + off + ", " + len + ", "
                    + pyBool(spec.signed()) + ")";
            case ALPHANUMERIC -> "decode_alphanumeric(self._data, " + off + ", " + len + ")";
        };
    }

    private static String encodeCall(AccessorSpec spec) {
        String off = spec.offsetExpression();
        int len = spec.byteLength();
        return switch (spec.kind()) {
            case PACKED -> "encode_packed(self._data, " + off + ", " + len + ", "
                    + pyBool(spec.signed()) + ", value)";
            case ZONED -> "encode_zoned(self._data, " + off + ", " + len + ", "
                    + pyBool(spec.signed()) + ", value)";
            case BINARY -> "encode_binary(self._data, " + off + ", " + len + ", "
                    + pyBool(spec.signed()) + ", value)";
            case ALPHANUMERIC -> "encode_alphanumeric(self._data, " + off + ", " + len + ", value)";
        };
    }

    /**
     * Maps one 88-level VALUE entry to a comparison expression against the parent item. If VALUE is
     * quoted, the comparison is done as a string even when the parent is a numeric item, following
     * the source notation as written. {@code low THRU high} expands into a lower/upper bound range
     * comparison. Figurative constants are mapped to their value, and any that cannot be mapped
     * because they depend on the code page are always rendered as a false expression with a note.
     */
    private static String condition(String call, FieldKind parentKind, String value) {
        if (Literals.isCodePageDependentFigurative(value)) {
            return "False  # " + value.trim() + " は文字コード系に依存するため対訳しない";
        }
        boolean asString = parentKind == FieldKind.ALPHANUMERIC || Literals.isQuoted(value);
        int thru = Literals.indexOfThru(value);
        if (thru >= 0) {
            String lo = value.substring(0, thru).trim();
            String hi = value.substring(thru + Literals.thruLength()).trim();
            return literal(lo, asString) + " <= " + call + " <= " + literal(hi, asString);
        }
        return call + " == " + literal(value, asString);
    }

    private static String literal(String value, boolean asString) {
        String bare = Literals.resolve(value);
        return asString ? "\"" + bare + "\"" : bare;
    }

    private static String pyBool(boolean value) {
        return value ? "True" : "False";
    }
}
