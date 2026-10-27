package org.ds.tuifileman.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void quoteEscapesWhatJsonRequires() {
        assertEquals("\"plain\"", Json.quote("plain"));
        assertEquals("\"a\\\"b\"", Json.quote("a\"b"));
        assertEquals("\"C:\\\\dir\\\\f.txt\"", Json.quote("C:\\dir\\f.txt"));
        assertEquals("\"l1\\nl2\\r\\t\"", Json.quote("l1\nl2\r\t"));
        assertEquals("\"\\u0001\\u001f\"", Json.quote("\u0001\u001f"));
        assertEquals("\"žluťoučký 日本\"", Json.quote("žluťoučký 日本"), "non-ASCII stays as it is");
        assertEquals("\"\"", Json.quote(""));
    }

    @Test
    void whatWeWriteWeCanRead() {
        String[] samples = {"", "plain", "quote \" and \\ backslash", "line\nbreak\ttab", "\u0000\u001f", "日本語 ✓",
                "C:\\Users\\x\\file (1).txt"};
        for (String sample : samples) {
            assertEquals(sample, JsonReader.parse(Json.quote(sample)));
        }
    }

    @Test
    void readsEveryJsonType() {
        Object parsed = JsonReader.parse("""
                {"s": "text", "n": -12.5, "i": 3, "t": true, "f": false, "z": null,
                 "list": [1, "two", [3], {"k": "v"}], "empty": {}, "none": []}
                """);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        assertEquals("text", map.get("s"));
        assertEquals(-12.5, map.get("n"));
        assertEquals(3.0, map.get("i"));
        assertEquals(true, map.get("t"));
        assertEquals(false, map.get("f"));
        assertNull(map.get("z"));
        assertEquals(true, map.containsKey("z"));
        assertEquals(List.of(1.0, "two", List.of(3.0), Map.of("k", "v")), map.get("list"));
        assertEquals(Map.of(), map.get("empty"));
        assertEquals(List.of(), map.get("none"));
    }

    @Test
    void keepsTheKeyOrder() {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) JsonReader.parse("{\"b\":1,\"a\":2,\"c\":3}");
        assertEquals(List.of("b", "a", "c"), List.copyOf(map.keySet()));
    }

    @Test
    void readsUnicodeEscapes() {
        assertEquals("é€", JsonReader.parse("\"\\u00e9\\u20ac\""));
        assertEquals("/\b\f", JsonReader.parse("\"\\/\\b\\f\""));
    }

    @Test
    void malformedInputIsRejectedWithAColumn() {
        for (String bad : new String[] {"", "{", "{\"a\"}", "{\"a\":}", "[1,", "[1 2]", "\"open", "\"bad\\q\"",
                "\"\\u12\"", "tru", "{1: 2}", "[1] x", "nul", "--1", "{\"a\":1,}"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> JsonReader.parse(bad),
                    "should reject: " + bad);
            assertEquals(true, error.getMessage().contains("column") || error.getMessage().contains("unexpected"),
                    error.getMessage());
        }
    }

    @Test
    void absurdNestingIsRejectedInsteadOfOverflowingTheStack() {
        String deep = "[".repeat(10_000) + "]".repeat(10_000);
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse(deep));
    }
}
