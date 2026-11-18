package org.ccg.mpp.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.fs.PkRebuild;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.resp.PrimaryKeyReindexResponse;
import org.ccg.mpp.ops.resp.ReindexResponse;
import org.junit.jupiter.api.Test;

public class ReindexResponseTest {
    @Test
    public void test_constructor_sets_reindex_operation_type() {
        ReindexResponse response = new ReindexResponse("ok", List.of("field1"));
        assertEquals(OperationType.REINDEX, response.getType());
    }

    @Test
    public void test_constructor_sets_status_and_message() {
        ReindexResponse response = new ReindexResponse("Rebuilt 1 index(es)", List.of("email"));
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Rebuilt 1 index(es)", response.getMessage());
    }

    @Test
    public void test_rebuilt_fields_is_set_correctly() {
        List<String> fields = List.of("email", "status");
        ReindexResponse response = new ReindexResponse("ok", fields);
        assertEquals(fields, response.getRebuiltFields());
        assertEquals(fields, response.rebuiltFields);
    }

    @Test
    public void test_null_rebuilt_fields_is_allowed() {
        ReindexResponse response = new ReindexResponse("msg", null);
        assertNull(response.getRebuiltFields());
    }

    @Test
    public void test_empty_rebuilt_fields() {
        ReindexResponse response = new ReindexResponse("No indexes to rebuild", List.of());
        assertTrue(response.getRebuiltFields().isEmpty());
    }

    @Test
    public void test_setter_updates_rebuilt_fields() {
        ReindexResponse response = new ReindexResponse("ok", List.of("a"));
        response.setRebuiltFields(List.of("b", "c"));
        assertEquals(List.of("b", "c"), response.getRebuiltFields());
    }

    @Test
    public void test_primary_key_response_carries_the_rebuild_counts_on_the_wire() {
        final var response = new PrimaryKeyReindexResponse("Rebuilt the primary-key index (3 entries)", List.of(),
                new PkRebuild(Collections.nCopies(3, new PkIndexEntry("db", "coll", "id", 0, 1, 0, 0)), 1, true,
                        List.of(), List.of()));

        final var json = IocContainer.get(EJson.class).toJson(response);

        assertTrue(json.contains("\"primaryKeyEntries\":3"), json);
        assertTrue(json.contains("\"unversionedEntries\":1"), json);
        assertTrue(json.contains("\"rebuiltFields\":[]"), json);
    }

    @Test
    public void test_primary_key_response_setters_update_the_counts() {
        final var response = new PrimaryKeyReindexResponse("ok", List.of(),
                new PkRebuild(List.of(), 0, true, List.of(), List.of()));
        response.setPrimaryKeyEntries(5);
        response.setUnversionedEntries(2);
        assertEquals(5, response.getPrimaryKeyEntries());
        assertEquals(2, response.getUnversionedEntries());
    }
}
