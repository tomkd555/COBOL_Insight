package jp.cobolinsight.core.json;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A minimal JSON writer. Builds compact JSON text in call order.
 * Used for deterministic serialization (e.g. JSON output of the call graph).
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

    // Inserts a separating comma before the second and later values within the same nesting level.
    // elementCounts holds the count of elements already emitted per nesting level. Right after
    // name(), no comma is added here because name() itself already emitted it.
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
                    // JSON strings cannot contain raw control characters as-is, so convert to a Unicode escape.
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
