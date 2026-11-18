package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Objects;
import java.util.UUID;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.req.RollbackTransactionRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.StartTransactionRequest;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TransactionWritesDeleteHookTest {
    private static final long COLD_HOOK_TIMEOUT_MS = 30_000L;
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private UUID client;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "beforeHookTimeoutMs", COLD_HOOK_TIMEOUT_MS);
        BeforeHookTestSupport.installHook("guard", "allow", "export default (d) => {};", EventType.DELETED);
        client = BeforeHookTestSupport.newClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        processor.processMessage(new RollbackTransactionRequest(), client);
        clientTracker.removeById(client);
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "beforeHookTimeoutMs",
                ConfigKey.BEFORE_HOOK_TIMEOUT_MS.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void saveCommitted(String id) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        request.setObject(object);
        request.set_id(id);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private OperationResponse bufferDelete(String id) {
        processor.processMessage(new StartTransactionRequest(), client);
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request, client);
    }

    private void makeTheVictimUnreadable() {
        cache.evictEntry(TestGlobals.DB, TestGlobals.COLL, "victim");
        final var folder = new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL);
        for (final var page : Objects.requireNonNull(folder.listFiles((_, name) -> name.endsWith(".dat")))) {
            assertTrue(page.delete());
        }
    }

    @Test
    public void test_an_unreadable_document_refuses_the_delete() {
        saveCommitted("victim");
        makeTheVictimUnreadable();

        final var response = bufferDelete("victim");

        assertEquals(ErrorCode.ERROR_DELETING.getCode(), response.getErrorCode(),
                "a veto hook that never ran must refuse the delete, as the standalone path does");
    }

    @Test
    public void test_a_missing_document_runs_no_hook() {
        assertNotEquals(ErrorCode.ERROR_DELETING.getCode(), bufferDelete("never-saved").getErrorCode());
    }

    @Test
    public void test_a_readable_document_still_runs_the_hook_and_buffers() {
        saveCommitted("kept");

        final var response = bufferDelete("kept");
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
    }
}
