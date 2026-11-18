package org.ccg.mpp.unit.ejson.elements;

import static org.ccg.mpp.test.EqualityChecks.assertDiffers;
import static org.ccg.mpp.test.EqualityChecks.assertEqualTo;
import static org.junit.jupiter.api.Assertions.*;

import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonNull;
import org.junit.jupiter.api.Test;

public class JsonNullTest {
    @Test
    public void test_singleton_instance_returns_same_object() {
        JsonNull instance1 = JsonNull.INSTANCE;
        JsonNull instance2 = JsonNull.INSTANCE;

        assertSame(instance1, instance2);
    }

    @Test
    public void test_equals_returns_false_for_null() {
        JsonNull jsonNull = JsonNull.INSTANCE;

        assertNotEquals(null, jsonNull);
    }

    @Test
    public void test_consistent_hashcode_for_all_instances() {
        JsonNull instance1 = JsonNull.INSTANCE;
        JsonNull instance2 = JsonNull.INSTANCE;
        JsonNull instance3 = new JsonNull();

        int hashCode1 = instance1.hashCode();
        int hashCode2 = instance2.hashCode();
        int hashCode3 = instance3.hashCode();

        assertEquals(hashCode1, hashCode2);
        assertEquals(hashCode2, hashCode3);
        assertEquals(hashCode1, JsonNull.class.hashCode());
    }

    @Test
    public void test_json_null_equals_another_json_null() {
        JsonNull jsonNull1 = JsonNull.INSTANCE;
        JsonNull jsonNull2 = JsonNull.INSTANCE;

        assertEqualTo(jsonNull1, jsonNull2);
    }

    @Test
    public void test_json_null_equals_null() {
        JsonNull jsonNull = JsonNull.INSTANCE;

        assertDiffers(jsonNull, null);
    }

    @Test
    public void test_deep_copy_returns_singleton_instance() {
        JsonNull jsonNull = new JsonNull();

        JsonBaseElement result = jsonNull.deepCopy();

        assertSame(JsonNull.INSTANCE, result);
    }

    @Test
    public void test_instance_not_null() {
        JsonNull jsonNull = JsonNull.INSTANCE;

        assertNotNull(jsonNull);

        JsonBaseElement copy = jsonNull.deepCopy();
        assertNotNull(copy);
    }
}
