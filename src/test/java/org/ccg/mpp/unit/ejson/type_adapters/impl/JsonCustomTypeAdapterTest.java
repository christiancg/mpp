package org.ccg.mpp.unit.ejson.type_adapters.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.ccg.mpp.ejson.custom_types.JsonTime;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonCustom;
import org.ccg.mpp.ejson.elements.JsonNull;
import org.ccg.mpp.ejson.type_adapters.TypeAdapter;
import org.ccg.mpp.ejson.type_adapters.TypeAdapterFactory;
import org.ccg.mpp.ejson.type_adapters.impl.JsonCustomTypeAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

public class JsonCustomTypeAdapterTest {

    @Test
    public void test_to_json_with_null_value() {
        JsonCustomTypeAdapter adapter = new JsonCustomTypeAdapter();

        assertThrows(NullPointerException.class, () -> adapter.toJson(null));
    }

    @Test
    public void test_json_custom_conversion() {
        JsonCustomTypeAdapter adapter = new JsonCustomTypeAdapter();

        JsonCustom<?> mockCustom = mock(JsonCustom.class);
        //noinspection unchecked
        TypeAdapter<JsonBaseElement> mockBaseAdapter = mock(TypeAdapter.class);

        try (MockedStatic<TypeAdapterFactory> factory = mockStatic(TypeAdapterFactory.class)) {
            factory.when(() -> TypeAdapterFactory.getAdapter(JsonBaseElement.class)).thenReturn(mockBaseAdapter);

            when(mockBaseAdapter.fromJson(mockCustom)).thenReturn(mockCustom);

            JsonCustom<?> result = adapter.fromJson(mockCustom);

            assertNotNull(result);
            assertEquals(mockCustom, result);
        }
    }

    @Test
    public void test_to_json_with_real_custom_type() {
        new org.ccg.mpp.ejson.EJson(); // register custom types
        JsonCustomTypeAdapter adapter = new JsonCustomTypeAdapter();
        JsonTime time = new JsonTime("#time(10:00:00)");
        String result = adapter.toJson(time);
        assertNotNull(result);
        assertTrue(result.contains("10:00:00"));
    }

    @Test
    public void test_from_json_with_non_custom_returns_null() {
        JsonCustomTypeAdapter adapter = new JsonCustomTypeAdapter();
        JsonCustom<?> result = adapter.fromJson(JsonNull.INSTANCE);
        assertNull(result);
    }
}
