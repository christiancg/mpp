package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.SaveSchemaRequest;
import org.junit.jupiter.api.Test;

public class SaveSchemaRequestTest {
    @Test
    public void test_constructor_and_getters() {
        final var schema = new JsonObject();
        schema.add("type", new org.ccg.mpp.ejson.elements.JsonString("object"));
        final var request = new SaveSchemaRequest("testProject", "testColl", schema);
        assertEquals(OperationType.SAVE_SCHEMA, request.getType());
        assertEquals("testProject", request.getProjectName());
        assertEquals("testColl", request.getCollectionName());
        assertEquals(schema, request.getSchema());
    }

    @Test
    public void test_setter() {
        final var request = new SaveSchemaRequest("testProject", "testColl", new JsonObject());
        final var newSchema = new JsonObject();
        newSchema.add("type", new org.ccg.mpp.ejson.elements.JsonString("string"));
        request.setSchema(newSchema);
        assertEquals(newSchema, request.getSchema());
    }
}
