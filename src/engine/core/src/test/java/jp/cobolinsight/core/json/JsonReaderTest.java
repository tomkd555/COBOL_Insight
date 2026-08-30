package jp.cobolinsight.core.json;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonReaderTest {

    @Test
    void readsObjectWithScalarValues() {
        Object value = JsonReader.parse("{\"a\":\"x\",\"n\":5,\"d\":1.5,\"f\":true,\"z\":null}");
        Map<String, Object> object = JsonReader.asObject(value);
        assertEquals("x", object.get("a"));
        assertEquals(5L, object.get("n"));
        assertEquals(1.5d, object.get("d"));
        assertEquals(Boolean.TRUE, object.get("f"));
        assertNull(object.get("z"));
        assertTrue(object.containsKey("z"));
    }

    @Test
    void readsNestedArraysAndObjects() {
        Object value = JsonReader.parse("{\"rules\":[{\"id\":\"U001\"},{\"id\":\"U002\"}]}");
        List<Object> rules = JsonReader.asArray(JsonReader.asObject(value).get("rules"));
        assertEquals(2, rules.size());
        assertEquals("U002", JsonReader.asObject(rules.get(1)).get("id"));
    }

    @Test
    void readsEscapeSequences() {
        Object value = JsonReader.parse("{\"p\":\"a\\\\b\\\"c\\n\\u0041\"}");
        assertEquals("a\\b\"c\nA", JsonReader.asObject(value).get("p"));
    }

    /** Confirms that a regex escape such as \\d can be written verbatim, as a precondition for user-defined rules. */
    @Test
    void readsRegexWithEscapedBackslash() {
        Object value = JsonReader.parse("{\"pattern\":\"\\\\bMOVE\\\\s+\\\\d+\"}");
        assertEquals("\\bMOVE\\s+\\d+", JsonReader.asObject(value).get("pattern"));
    }

    @Test
    void ignoresWhitespaceBetweenTokens() {
        Object value = JsonReader.parse("  {\n  \"a\" : [ 1 , 2 ]\n}  ");
        assertEquals(List.of(1L, 2L), JsonReader.asArray(JsonReader.asObject(value).get("a")));
    }

    @Test
    void readsNegativeAndExponentNumbers() {
        List<Object> values = JsonReader.asArray(JsonReader.parse("[-3,2e2,-1.5e-1]"));
        assertEquals(-3L, values.get(0));
        assertEquals(200.0d, values.get(1));
        assertEquals(-0.15d, values.get(2));
    }

    @Test
    void rejectsTrailingContent() {
        assertThrows(JsonReader.JsonParseException.class, () -> JsonReader.parse("{} {}"));
    }

    @Test
    void rejectsUnterminatedObject() {
        assertThrows(JsonReader.JsonParseException.class, () -> JsonReader.parse("{\"a\":1"));
    }

    @Test
    void rejectsTrailingComma() {
        assertThrows(JsonReader.JsonParseException.class, () -> JsonReader.parse("[1,2,]"));
    }

    @Test
    void rejectsSingleQuotedString() {
        assertThrows(JsonReader.JsonParseException.class, () -> JsonReader.parse("{'a':1}"));
    }

    @Test
    void asObjectRejectsNonObject() {
        assertThrows(JsonReader.JsonParseException.class, () -> JsonReader.asObject("x"));
    }

    @Test
    void asArrayRejectsNonArray() {
        assertThrows(JsonReader.JsonParseException.class, () -> JsonReader.asArray("x"));
    }
}
