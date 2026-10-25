package io.jenkins.plugins.concierge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void stringsThatLookLikeJsonStayStrings() {
        // The reason this class exists: json-lib would turn these into an array, an object and null.
        for (String tricky : List.of("[1, 2]", "{\"a\":1}", "null", "true", "  [x]  ")) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("text", tricky);
            String written = Json.write(map);
            assertEquals(tricky, Json.parseObject(written).get("text"), written);
        }
    }

    @Test
    void roundTripsNestedValues() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("s", "line one\nline \"two\"\t\\ \u0001 \u2028");
        value.put("i", 42);
        value.put("l", 12345678901L);
        value.put("d", new BigDecimal("1.5"));
        value.put("b", false);
        value.put("n", null);
        value.put("list", List.of(1, "two", Map.of("three", 3)));
        assertEquals(value, Json.parse(Json.write(value)));
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad :
                List.of("", "{", "[1,]", "{\"a\" 1}", "\"unterminated", "tru", "{} extra", "\"\\x\"", "01a")) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
        }
    }

    @Test
    void limitsNesting() {
        String deep = "[".repeat(Json.MAX_DEPTH + 5) + "]".repeat(Json.MAX_DEPTH + 5);
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deep));
    }

    @Test
    void parsesUnicodeEscapesAndLiterals() {
        assertEquals("é✓", Json.parse("\"\\u00e9\\u2713\""));
        assertNull(Json.parse(" null "));
        assertEquals(List.of(true, false), Json.parse("[true,false]"));
        assertEquals(-3, Json.parse("-3"));
    }

    @Test
    void accessorsTolerateMissingOrWrongTypes() {
        Map<String, Object> map = Json.parseObject("{\"a\":{\"b\":1},\"c\":[1],\"d\":\"x\"}");
        assertEquals(Map.of("b", 1), Json.object(map, "a"));
        assertEquals(Map.of(), Json.object(map, "c"));
        assertEquals(List.of(1), Json.array(map, "c"));
        assertEquals(List.of(), Json.array(map, "d"));
        assertEquals("x", Json.string(map, "d", "default"));
        assertEquals("default", Json.string(map, "a", "default"));
        assertEquals("default", Json.string(null, "a", "default"));
    }
}
