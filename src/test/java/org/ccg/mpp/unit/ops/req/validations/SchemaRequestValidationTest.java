package org.ccg.mpp.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ops.req.DeleteSchemaRequest;
import org.ccg.mpp.ops.req.SaveSchemaRequest;
import org.ccg.mpp.ops.req.validations.RequestValidator;
import org.junit.jupiter.api.Test;

public class SchemaRequestValidationTest {
    private JsonObject sampleSchema() {
        final var schema = new JsonObject();
        schema.add("type", new JsonString("object"));
        return schema;
    }

    @Test
    public void test_save_schema_valid() {
        final var request = new SaveSchemaRequest("myDb", "myColl", sampleSchema());
        assertTrue(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_save_schema_missing_schema() {
        final var request = new SaveSchemaRequest("myDb", "myColl", null);
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_save_schema_empty_schema() {
        final var request = new SaveSchemaRequest("myDb", "myColl", new JsonObject());
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_save_schema_bad_collection() {
        final var request = new SaveSchemaRequest("myDb", "x", sampleSchema());
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_save_schema_reserved_db() {
        final var request = new SaveSchemaRequest("admin", "myColl", sampleSchema());
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_delete_schema_valid() {
        final var request = new DeleteSchemaRequest("myDb", "myColl");
        assertTrue(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_delete_schema_blank_db() {
        final var request = new DeleteSchemaRequest("", "myColl");
        assertFalse(RequestValidator.validate(request).isValid());
    }
}
