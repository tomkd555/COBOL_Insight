package jp.cobolinsight.engineapi.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小限のJSONパーサ。{@link JsonWriter} の対として、外部から与えられる小さなJSON
 * (利用者定義ルールの定義ファイル)を読む。値は Map・List・String・Long・Double・Boolean・null
 * へ写す。整数と小数を Long と Double で区別するのは、版数や桁数を整数のまま扱うためである。
 *
 * <p>利用者が手で書いたファイルを読むため、末尾のごみ・閉じ忘れ・末尾カンマ・単引用符を
 * いずれも受け付けず、位置を添えた {@link JsonParseException} で拒む。
 */
public final class JsonReader {

    /** 構文の誤りと、期待しない型。いずれも利用者へ位置または内容を示して伝える。 */
    public static final class JsonParseException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        public JsonParseException(String message) {
            super(message);
        }
    }

    private final String text;
    private int pos;

    private JsonReader(String text) {
        this.text = text;
    }

    /** JSONテキスト全体を1つの値として読む。末尾に余分な文字があれば拒む。 */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonParseException("JSONテキストが null である");
        }
        JsonReader reader = new JsonReader(text);
        reader.skipWhitespace();
        Object value = reader.readValue();
        reader.skipWhitespace();
        if (reader.pos < text.length()) {
            throw reader.error("値の後に余分な文字がある");
        }
        return value;
    }

    /** オブジェクトとして取り出す。キーの順は記述順を保つ。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new JsonParseException("オブジェクトを期待したが " + typeNameOf(value) + " である");
        }
        return (Map<String, Object>) map;
    }

    /** 配列として取り出す。 */
    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new JsonParseException("配列を期待したが " + typeNameOf(value) + " である");
        }
        return (List<Object>) list;
    }

    private static String typeNameOf(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map) {
            return "オブジェクト";
        }
        if (value instanceof List) {
            return "配列";
        }
        if (value instanceof String) {
            return "文字列";
        }
        if (value instanceof Boolean) {
            return "真偽値";
        }
        return "数値";
    }

    private Object readValue() {
        if (pos >= text.length()) {
            throw error("値が無いまま入力が尽きた");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readKeyword("true", Boolean.TRUE);
            case 'f' -> readKeyword("false", Boolean.FALSE);
            case 'n' -> readKeyword("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> object = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return object;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("オブジェクトのキーは二重引用符で囲む");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            object.put(key, readValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return object;
            }
            if (c != ',') {
                throw error("オブジェクトの要素の区切りが不正である");
            }
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> array = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(readValue());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return array;
            }
            if (c != ',') {
                throw error("配列の要素の区切りが不正である");
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                if (c < 0x20) {
                    throw error("文字列に生の制御文字がある");
                }
                out.append(c);
                continue;
            }
            char escaped = next();
            switch (escaped) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(readUnicodeEscape());
                default -> throw error("扱えないエスケープ \\" + escaped + " がある");
            }
        }
    }

    private char readUnicodeEscape() {
        if (pos + 4 > text.length()) {
            throw error("\\u に続く4桁が足りない");
        }
        String digits = text.substring(pos, pos + 4);
        pos += 4;
        try {
            return (char) Integer.parseInt(digits, 16);
        } catch (NumberFormatException e) {
            throw error("\\u に続く4桁が16進数でない: " + digits);
        }
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        boolean fractional = false;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                fractional = true;
                pos++;
            } else {
                break;
            }
        }
        String token = text.substring(start, pos);
        if (token.isEmpty() || token.equals("-")) {
            throw error("数値として読めない");
        }
        try {
            return fractional ? (Object) Double.parseDouble(token) : (Object) Long.parseLong(token);
        } catch (NumberFormatException e) {
            throw error("数値として読めない: " + token);
        }
    }

    private Object readKeyword(String keyword, Object value) {
        if (!text.startsWith(keyword, pos)) {
            throw error("値として読めない");
        }
        pos += keyword.length();
        return value;
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("入力が尽きた");
        }
        return text.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char expected) {
        char c = next();
        if (c != expected) {
            throw error("'" + expected + "' を期待したが '" + c + "' である");
        }
    }

    /** 位置は0起点の文字位置で示す。行番号を持たないのは、対象が小さな定義ファイルのためである。 */
    private JsonParseException error(String message) {
        return new JsonParseException(message + " (位置 " + pos + ")");
    }
}
