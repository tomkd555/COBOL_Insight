package jp.cobolinsight.engineapi.json;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonWriterTest {

    @Test
    void writesCompactObjectWithCommas() {
        JsonWriter w = new JsonWriter();
        w.beginObject()
                .name("a").value("x")
                .name("n").value(5)
                .name("flag").value(true)
                .endObject();
        assertEquals("{\"a\":\"x\",\"n\":5,\"flag\":true}", w.toString());
    }

    @Test
    void writesNestedArraysAndObjects() {
        JsonWriter w = new JsonWriter();
        w.beginObject()
                .name("arr").beginArray()
                .value("1")
                .beginObject().name("k").value("v").endObject()
                .endArray()
                .endObject();
        assertEquals("{\"arr\":[\"1\",{\"k\":\"v\"}]}", w.toString());
    }

    @Test
    void escapesQuotesBackslashesAndControlCharacters() {
        JsonWriter w = new JsonWriter();
        w.beginObject().name("s").value("a\"b\\c\nd\te\u0001f").endObject();
        assertEquals("{\"s\":\"a\\\"b\\\\c\\nd\\te\\u0001f\"}", w.toString());
    }

    @Test
    void writesEmptyObjectAndArray() {
        JsonWriter w = new JsonWriter();
        w.beginArray().beginObject().endObject().beginArray().endArray().endArray();
        assertEquals("[{},[]]", w.toString());
    }
}
