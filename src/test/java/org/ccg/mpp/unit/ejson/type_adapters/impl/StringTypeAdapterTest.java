package org.ccg.mpp.unit.ejson.type_adapters.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ejson.type_adapters.impl.StringTypeAdapter;
import org.junit.jupiter.api.Test;

public class StringTypeAdapterTest {
    @Test
    public void test_to_json_adds_quotes_to_string() {
        StringTypeAdapter adapter = new StringTypeAdapter();
        String input = "test string";
        String result = adapter.toJson(input);
        assertEquals("\"test string\"", result);
    }

    @Test
    public void test_to_json_handles_null_input() {
        StringTypeAdapter adapter = new StringTypeAdapter();
        String result = adapter.toJson(null);
        assertEquals("null", result);
    }

    @Test
    public void test_string_value_converted_to_json_format() {
        StringTypeAdapter adapter = new StringTypeAdapter();
        String input = "test";
        String result = adapter.toJson(input);
        assertEquals("\"test\"", result);
    }

    @Test
    public void test_null_value_converted_to_null_string() {
        StringTypeAdapter adapter = new StringTypeAdapter();
        String result = adapter.toJson(null);
        assertEquals("null", result);
    }

    @Test
    public void test_json_string_returns_value() {
        JsonString jsonString = new JsonString("test value");
        StringTypeAdapter converter = new StringTypeAdapter();

        String result = converter.fromJson(jsonString);

        assertEquals("test value", result);
    }

    @Test
    public void test_null_json_element_returns_null() {
        JsonNumber jsonNumber = new JsonNumber(123);
        StringTypeAdapter converter = new StringTypeAdapter();

        String result = converter.fromJson(jsonNumber);

        assertNull(result);
    }
}
