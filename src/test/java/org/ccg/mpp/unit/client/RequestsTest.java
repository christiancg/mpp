package org.ccg.mpp.unit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.ccg.mpp.client.Requests;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.junit.jupiter.api.Test;

public class RequestsTest {
    private final EJson eJson = IocContainer.get(EJson.class);

    private String json(JsonObject request) {
        return eJson.toJson(request);
    }

    private JsonObject parse(String json) {
        return eJson.fromJson(json, JsonObject.class);
    }

    @Test
    public void test_authenticate() {
        assertEquals("{\"type\":\"AUTHENTICATE\",\"username\":\"admin\",\"password\":\"pw\"}",
                json(Requests.authenticate("admin", "pw")));
    }

    @Test
    public void test_connection_level_requests() {
        assertEquals("{\"type\":\"CLOSE_CONNECTION\"}", json(Requests.close()));
        assertEquals("{\"type\":\"LIST_PROJECTS\"}", json(Requests.listProjects()));
        assertEquals("{\"type\":\"GET_PROJECT_STATS\"}", json(Requests.stats()));
        assertEquals("{\"type\":\"SERVER_INFO\"}", json(Requests.serverInfo()));
    }

    @Test
    public void test_project_requests() {
        assertEquals("{\"type\":\"LIST_COLLECTIONS\",\"projectName\":\"shop\"}",
                json(Requests.listCollections("shop")));
        assertEquals("{\"type\":\"CREATE_PROJECT\",\"projectName\":\"shop\"}", json(Requests.createProject("shop")));
        assertEquals("{\"type\":\"LIST_TRIGGERS\",\"projectName\":\"shop\"}", json(Requests.listTriggers("shop")));
        assertEquals("{\"type\":\"LIST_PROCEDURES\",\"projectName\":\"shop\",\"includeSource\":true}",
                json(Requests.listProcedures("shop", true)));
        assertEquals("{\"type\":\"LIST_SCHEDULES\",\"projectName\":\"shop\",\"includeArgs\":false}",
                json(Requests.listSchedules("shop", false)));
        assertEquals("{\"type\":\"LIST_ENTRYPOINTS\",\"projectName\":\"shop\"}",
                json(Requests.listEntrypoints("shop")));
    }

    @Test
    public void test_collection_requests() {
        assertEquals("{\"type\":\"CREATE_COLLECTION\",\"projectName\":\"shop\",\"collectionName\":\"orders\"}",
                json(Requests.createCollection("shop", "orders")));
        assertEquals("{\"type\":\"GET_SCHEMA\",\"projectName\":\"shop\",\"collectionName\":\"orders\"}",
                json(Requests.getSchema("shop", "orders")));
        assertEquals(
                "{\"type\":\"CREATE_INDEX\",\"projectName\":\"shop\",\"collectionName\":\"orders\","
                        + "\"fieldNames\":[\"customer\",\"total\"]}",
                json(Requests.createIndexes("shop", "orders", List.of("customer", "total"))));
        assertEquals(
                "{\"type\":\"SAVE_SCHEMA\",\"projectName\":\"shop\",\"collectionName\":\"orders\","
                        + "\"schema\":{\"type\":\"object\"}}",
                json(Requests.saveSchema("shop", "orders", parse("{\"type\":\"object\"}"))));
    }

    @Test
    public void test_start_snapshot() {
        assertEquals(
                "{\"type\":\"START_SNAPSHOT\",\"projectName\":\"shop\",\"collectionNames\":[\"orders\",\"leads\"]}",
                json(Requests.startSnapshot("shop", List.of("orders", "leads"))));
    }

    @Test
    public void test_read_snapshot_from_the_start_and_after_an_id() {
        assertEquals(
                "{\"type\":\"READ_SNAPSHOT\",\"projectName\":\"shop\",\"collectionName\":\"orders\","
                        + "\"snapshotId\":\"s1\",\"limit\":1000}",
                json(Requests.readSnapshot("shop", "orders", "s1", null, 1000)));
        assertEquals(
                "{\"type\":\"READ_SNAPSHOT\",\"projectName\":\"shop\",\"collectionName\":\"orders\","
                        + "\"snapshotId\":\"s1\",\"afterId\":\"o-9\",\"limit\":10}",
                json(Requests.readSnapshot("shop", "orders", "s1", "o-9", 10)));
    }

    @Test
    public void test_release_snapshot() {
        assertEquals("{\"type\":\"RELEASE_SNAPSHOT\",\"projectName\":\"shop\",\"snapshotId\":\"s1\"}",
                json(Requests.releaseSnapshot("shop", "s1")));
    }

    @Test
    public void test_bulk_save() {
        final var documents = new JsonArray();
        documents.add(parse("{\"_id\":\"o1\",\"at\":\"#datetime(2026-01-01T00:00:00)\"}"));
        assertEquals(
                "{\"type\":\"BULK_SAVE\",\"projectName\":\"shop\",\"collectionName\":\"orders\","
                        + "\"objects\":[{\"_id\":\"o1\",\"at\":\"#datetime(2026-01-01T00:00:00)\"}]}",
                json(Requests.bulkSave("shop", "orders", documents)));
    }

    @Test
    public void test_save_procedure_maps_source_to_script() {
        final var listed = parse("{\"name\":\"total\",\"source\":\"export default 1;\",\"sourceHash\":\"ab\","
                + "\"description\":\"sums\",\"enabled\":false,\"version\":4,\"createdAt\":1,\"updatedAt\":2,"
                + "\"updatedBy\":\"bob\"}");
        assertEquals(
                "{\"type\":\"SAVE_PROCEDURE\",\"projectName\":\"shop\",\"name\":\"total\","
                        + "\"script\":\"export default 1;\",\"description\":\"sums\",\"enabled\":false}",
                json(Requests.saveProcedureFrom("shop", listed)));
    }

    @Test
    public void test_save_trigger_drops_version_definer_and_audit_fields() {
        final var listed = parse("{\"name\":\"audit\",\"events\":[\"CREATED\"],\"procedureName\":\"total\","
                + "\"mode\":\"document\",\"timing\":\"after\",\"allowCascade\":false,\"enabled\":true,"
                + "\"definer\":\"bob\",\"version\":3,\"createdAt\":1,\"updatedAt\":2,\"disabledReason\":null,"
                + "\"collectionName\":\"orders\"}");
        assertEquals("{\"type\":\"SAVE_TRIGGER\",\"projectName\":\"shop\",\"collectionName\":\"orders\","
                + "\"name\":\"audit\",\"events\":[\"CREATED\"],\"procedureName\":\"total\",\"mode\":\"document\","
                + "\"timing\":\"after\",\"allowCascade\":false,\"enabled\":true}",
                json(Requests.saveTriggerFrom("shop", listed)));
    }

    @Test
    public void test_save_schedule_keeps_args_and_cron_or_interval() {
        final var cron = parse("{\"name\":\"nightly\",\"procedureName\":\"total\",\"cron\":\"0 3 * * *\","
                + "\"intervalMs\":0,\"args\":{\"limit\":7},\"timeoutMs\":0,\"enabled\":true,\"definer\":\"bob\","
                + "\"version\":2,\"nextRunAt\":5,\"owner\":\"n1\"}");
        assertEquals("{\"type\":\"SAVE_SCHEDULE\",\"projectName\":\"shop\",\"name\":\"nightly\","
                + "\"procedureName\":\"total\",\"cron\":\"0 3 * * *\",\"args\":{\"limit\":7},\"timeoutMs\":0,"
                + "\"enabled\":true}", json(Requests.saveScheduleFrom("shop", cron)));
        final var interval = parse("{\"name\":\"tick\",\"procedureName\":\"total\",\"intervalMs\":60000,"
                + "\"args\":{},\"timeoutMs\":500,\"description\":\"every minute\",\"enabled\":false}");
        assertEquals(
                "{\"type\":\"SAVE_SCHEDULE\",\"projectName\":\"shop\",\"name\":\"tick\","
                        + "\"procedureName\":\"total\",\"intervalMs\":60000,\"args\":{},\"timeoutMs\":500,"
                        + "\"description\":\"every minute\",\"enabled\":false}",
                json(Requests.saveScheduleFrom("shop", interval)));
    }
}
