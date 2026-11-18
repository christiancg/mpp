package org.ccg.mpp.unit.ejson.type_adapters.impl;

import static org.junit.jupiter.api.Assertions.*;

import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonBoolean;
import org.ccg.mpp.ejson.elements.JsonNull;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.type_adapters.impl.JsonBaseElementTypeAdapter;
import org.junit.jupiter.api.Test;

public class JsonBaseElementTypeAdapterTest {
    @Test
    public void test_boolean_element_to_json_string() {
        new EJson();
        JsonBaseElementTypeAdapter adapter = new JsonBaseElementTypeAdapter();

        JsonBoolean boolElement = JsonBoolean.of(true);

        String result = adapter.toJson(boolElement);

        assertEquals("true", result);
    }

    @Test
    public void test_null_input_value() {
        JsonBaseElementTypeAdapter adapter = new JsonBaseElementTypeAdapter();

        assertThrows(NullPointerException.class, () -> adapter.toJson(null));
    }

    @Test
    public void test_json_null_element_converts_to_null_string() {
        JsonBaseElementTypeAdapter adapter = new JsonBaseElementTypeAdapter();
        JsonNull jsonNull = new JsonNull();

        String result = adapter.toJson(jsonNull);

        assertEquals("null", result);
    }

    @Test
    public void test_null_input_throws_exception() {
        JsonBaseElementTypeAdapter adapter = new JsonBaseElementTypeAdapter();

        assertThrows(NullPointerException.class, () -> adapter.toJson(null));
    }

    @Test
    public void test_returns_same_json_base_element_instance() {
        JsonBaseElementTypeAdapter adapter = new JsonBaseElementTypeAdapter();

        JsonBaseElement input = new JsonObject();

        JsonBaseElement result = adapter.fromJson(input);

        assertSame(input, result);
    }

    @Test
    public void test_accepts_null_input() {
        JsonBaseElementTypeAdapter adapter = new JsonBaseElementTypeAdapter();

        JsonBaseElement result = adapter.fromJson(null);

        assertNull(result);
    }
}
