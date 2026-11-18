package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.CompiledProcedureCache;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.TransactionOperationHelper;
import org.ccg.mpp.ops.req.CallProcedureRequest;
import org.ccg.mpp.ops.req.CreateDatabaseRequest;
import org.ccg.mpp.ops.req.DeleteProcedureRequest;
import org.ccg.mpp.ops.req.DropDatabaseRequest;
import org.ccg.mpp.ops.req.ListProceduresRequest;
import org.ccg.mpp.ops.req.ListTriggersRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.resp.ListProceduresResponse;
import org.ccg.mpp.ops.resp.SaveProcedureResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class OperationProcessorProcedureTest {
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 10_000_000L);
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "scriptMaxDepth", 200);
        TestUtils.setPrivateField(configuration, "scriptMaxMemoryBytes", 67_108_864L);
        for (final var name : fs.metadataFiles().listProcedureNames(TestGlobals.DB)) {
            fs.metadataFiles().deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
        // A leftover cached trigger would make a procedure delete fail the reference check
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        IocContainer.get(CompiledProcedureCache.class).invalidateDatabase(TestGlobals.DB);
    }

    @Test
    public void test_dispatches_save_call_list_and_delete() {
        assertInstanceOf(SaveProcedureResponse.class,
                processor.processMessage(new SaveProcedureRequest(TestGlobals.DB, "p", "return 7;")));
        final var listed = (ListProceduresResponse) processor.processMessage(new ListProceduresRequest(TestGlobals.DB));
        assertEquals(1, listed.getProcedures().size());
        final var called = processor.processMessage(new CallProcedureRequest(TestGlobals.DB, "p", null));
        assertEquals(OperationStatus.OK, called.getStatus(), called.getMessage());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new DeleteProcedureRequest(TestGlobals.DB, "p")).getStatus());
    }

    @Test
    public void test_dispatches_list_triggers() {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new ListTriggersRequest(TestGlobals.DB, TestGlobals.COLL)).getStatus());
    }

    // A procedure opens its own transaction through db.transaction; nesting is not supported, so all four
    // ops are refused while a client transaction is open. isAllowedDuringTransaction is default-deny -
    // this pins it, so adding them to that allow-list later has to be justified.
    @Test
    public void test_procedure_ops_are_refused_inside_an_open_transaction() {
        final var clientId = IocContainer.get(ClientTracker.class).registerForwardedClient("someone");
        assertEquals(OperationStatus.OK, TransactionOperationHelper.start(clientId).getStatus());
        try {
            for (final var request : java.util.List.of(new SaveProcedureRequest(TestGlobals.DB, "p", "return 1;"),
                    new DeleteProcedureRequest(TestGlobals.DB, "p"), new ListProceduresRequest(TestGlobals.DB),
                    new CallProcedureRequest(TestGlobals.DB, "p", null))) {
                final var response = processor.processMessage(request, clientId);
                assertEquals(ErrorCode.OPERATION_NOT_ALLOWED_IN_TRANSACTION.getCode(), response.getErrorCode(),
                        request.getType().name());
            }
        } finally {
            TransactionOperationHelper.rollback(clientId);
        }
    }

    // Dropping the database takes its procedure files with the folder; the cached definitions and the
    // compiled programs must go too, because a re-created database restarts its versions at 1.
    @Test
    public void test_drop_database_removes_procedures_and_their_compiled_programs() throws Exception {
        final var dbName = "procdropdb";
        assertEquals(OperationStatus.OK, processor.processMessage(new CreateDatabaseRequest(dbName)).getStatus());
        assertInstanceOf(SaveProcedureResponse.class,
                processor.processMessage(new SaveProcedureRequest(dbName, "p", "return 1;")));
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CallProcedureRequest(dbName, "p", null)).getStatus());

        assertEquals(OperationStatus.OK, processor.processMessage(new DropDatabaseRequest(dbName)).getStatus());
        assertNull(cache.getProcedure(dbName, "p"));
        assertTrue(fs.metadataFiles().listProcedureNames(dbName).isEmpty());

        assertEquals(OperationStatus.OK, processor.processMessage(new CreateDatabaseRequest(dbName)).getStatus());
        assertInstanceOf(SaveProcedureResponse.class,
                processor.processMessage(new SaveProcedureRequest(dbName, "p", "return 2;")));
        final var called = processor.processMessage(new CallProcedureRequest(dbName, "p", null));
        assertEquals(2d, ((org.ccg.mpp.ops.resp.CallProcedureResponse) called).getResult().asJsonNumber().getValue()
                .doubleValue());
        processor.processMessage(new DropDatabaseRequest(dbName));
    }

    @Test
    public void test_save_failure_is_reported_as_an_error_code() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        final var response = processor.processMessage(new SaveProcedureRequest(TestGlobals.DB, "p", "return 1;"));
        assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(), response.getErrorCode());
    }

    private static UUID unusedClient() {
        return UUID.randomUUID();
    }

    @Test
    public void test_call_with_an_unknown_client_id_still_runs() {
        processor.processMessage(new SaveProcedureRequest(TestGlobals.DB, "p", "return 1;"));
        assertEquals(OperationStatus.OK, processor
                .processMessage(new CallProcedureRequest(TestGlobals.DB, "p", null), unusedClient()).getStatus());
    }
}
