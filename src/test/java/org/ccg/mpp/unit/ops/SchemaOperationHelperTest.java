package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.DeleteSchemaRequest;
import org.ccg.mpp.ops.req.GetSchemaRequest;
import org.ccg.mpp.ops.req.SaveSchemaRequest;
import org.ccg.mpp.ops.resp.GetSchemaResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SchemaOperationHelperTest {
    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"_id\"]}";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    @BeforeAll
    static void setUp() throws Exception {
        BeforeHookTestSupport.setUpAll();
    }

    @AfterAll
    static void tearDown() throws Exception {
        BeforeHookTestSupport.tearDownAll();
    }

    @BeforeEach
    void reset() {
        processor.processMessage(new DeleteSchemaRequest(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    @Test
    public void test_get_schema_returns_saved_schema() {
        final var saved = eJson.fromJson(SCHEMA, JsonObject.class);
        processor.processMessage(new SaveSchemaRequest(TestGlobals.PROJECT, TestGlobals.COLL, saved));

        final var response = processor.processMessage(new GetSchemaRequest(TestGlobals.PROJECT, TestGlobals.COLL));

        final var schema = assertInstanceOf(GetSchemaResponse.class, response).getSchema();
        assertEquals(OperationType.GET_SCHEMA, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(saved, schema);
        assertNotSame(cache.getCollectionSchema(TestGlobals.PROJECT, TestGlobals.COLL), schema);
    }

    @Test
    public void test_get_schema_without_one_is_404_12() {
        final var response = processor.processMessage(new GetSchemaRequest(TestGlobals.PROJECT, TestGlobals.COLL));

        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals(ErrorCode.SCHEMA_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_get_schema_unknown_collection_is_404_11() {
        final var response = processor.processMessage(new GetSchemaRequest(TestGlobals.PROJECT, "missingColl"));

        assertEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_get_schema_of_an_unknown_collection_leaves_no_collection_lock() throws Exception {
        final var ghost = "ghostSchemaColl";

        final var response = processor.processMessage(new GetSchemaRequest(TestGlobals.PROJECT, ghost));

        assertEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(), response.getErrorCode());
        final Map<?, ?> locks = TestUtils.getPrivateStaticField(ResourceLocking.class, "locks", Map.class);
        assertFalse(locks.containsKey(Cache.getCollectionIdentifier(TestGlobals.PROJECT, ghost)),
                "a name nobody registered must not keep a collection lock after the read hold is released");
    }

    @Test
    public void test_unreadable_schema_is_503_11() throws Exception {
        processor.processMessage(
                new SaveSchemaRequest(TestGlobals.PROJECT, TestGlobals.COLL, eJson.fromJson(SCHEMA, JsonObject.class)));
        Files.writeString(schemaFile(), "{not json", StandardCharsets.UTF_8);
        cache.removeCollectionSchema(TestGlobals.PROJECT, TestGlobals.COLL);

        final var response = processor.processMessage(new GetSchemaRequest(TestGlobals.PROJECT, TestGlobals.COLL));

        assertEquals(ErrorCode.SCHEMA_UNAVAILABLE.getCode(), response.getErrorCode());
    }

    private static Path schemaFile() {
        return Path.of(TestGlobals.PATH, TestGlobals.PROJECT, TestGlobals.COLL, TestGlobals.COLL
                + Globals.INDEX_FILE_NAME_SEPARATOR + Globals.SCHEMA_FILE_NAME + Globals.SCHEMA_FILE_EXTENSION);
    }
}
