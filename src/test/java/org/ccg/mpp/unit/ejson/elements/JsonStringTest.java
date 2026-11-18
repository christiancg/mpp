package org.ccg.mpp.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.ccg.mpp.ejson.elements.JsonString;
import org.junit.jupiter.api.Test;

public class JsonStringTest {
    @Test
    public void test_empty_constructor_initializes_empty_string() {
        JsonString jsonString = new JsonString();

        assertEquals("", jsonString.getValue());
    }

    @Test
    public void test_constructor_with_null_value() {
        JsonString jsonString = new JsonString(null);

        assertNull(jsonString.getValue());
    }

    @Test
    public void test_constructor_sets_value_field() {
        String testValue = "test string";
        JsonString jsonString = new JsonString(testValue);

        assertEquals(testValue, jsonString.getValue());
    }

    @Test
    public void test_constructor_handles_null_input() {
        JsonString jsonString = new JsonString(null);

        assertNull(jsonString.getValue());
    }
}
