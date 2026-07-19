package jp.cobolinsight.engineapi.json;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 最小限のJSONライター。呼出順のとおりにコンパクトなJSONテキストを組み立てる。
 * 決定論的な直列化(呼出関係グラフのJSON正本など)に用いる。
 */
public final class JsonWriter {

    private final StringBuilder out = new StringBuilder();
    private final Deque<int[]> elementCounts = new ArrayDeque<>();
    private boolean pendingName;

    public JsonWriter beginObject() {
        beforeValue();
        out.append('{');
        elementCounts.push(new int[1]);
        return this;
    }

    public JsonWriter endObject() {
        elementCounts.pop();
        out.append('}');
        return this;
    }

    public JsonWriter beginArray() {
        beforeValue();
        out.append('[');
        elementCounts.push(new int[1]);
        return this;
    }

    public JsonWriter endArray() {
        elementCounts.pop();
        out.append(']');
        return this;
    }

    public JsonWriter name(String name) {
        int[] count = elementCounts.element();
        if (count[0] > 0) {
            out.append(',');
        }
        count[0]++;
        out.append('"').append(escape(name)).append('"').append(':');
        pendingName = true;
        return this;
    }

    public JsonWriter value(String value) {
        beforeValue();
        out.append('"').append(escape(value)).append('"');
        return this;
    }

    public JsonWriter value(long value) {
        beforeValue();
        out.append(value);
        return this;
    }

    public JsonWriter value(boolean value) {
        beforeValue();
        out.append(value);
        return this;
    }

    private void beforeValue() {
        if (pendingName) {
            pendingName = false;
            return;
        }
        int[] count = elementCounts.peek();
        if (count != null) {
            if (count[0] > 0) {
                out.append(',');
            }
            count[0]++;
        }
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
