package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.UUID;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.data.admin.AdminTransactionEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ex.LoggedWriteNotAppliedException;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.TransactionOperationHelper;
import org.ccg.mpp.ops.TxCommitLog;
import org.ccg.mpp.ops.admin.DeferredWrites;
import org.ccg.mpp.ops.admin.TransactionRecords;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

public class TransactionFenceRedriveTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final String collId = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
    private UUID clientId;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        DeferredWrites.install();
        final var seed = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        seed.setObject(document("seed"));
        seed.set_id("seed");
        assertEquals(OperationStatus.OK, processor.processMessage(seed).getStatus());
        clientId = clientTracker.registerForwardedClient("redriver");
        TestUtils.ensureAdminUser(clientTracker.getAuthenticatedUsername(clientId));
        TransactionOperationHelper.start(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document("fenced-doc"));
        request.set_id("fenced-doc");
        TransactionOperationHelper.bufferSave(request, clientTracker.getActiveTransaction(clientId));
    }

    @AfterEach
    public void tearDown() throws Exception {
        writablePages(true);
        TestUtils.setPrivateStaticField(TransactionRecords.class, "cache", cache);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction != null) {
            TxCommitLog.clearLocalCommit(transaction.getTransactionId().toString());
        }
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("value", new JsonString("v"));
        return object;
    }

    private static void writablePages(boolean writable) {
        final var files = new File(TestGlobals.PATH + "/" + TestGlobals.DB + "/" + TestGlobals.COLL)
                .listFiles((_, name) -> name.endsWith(".dat"));
        for (final var page : files == null ? new File[0] : files) {
            assertTrue(page.setWritable(writable));
        }
    }

    private boolean fencedDocumentExists() throws Exception {
        return !cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, Set.of("fenced-doc")).isEmpty();
    }

    @Test
    public void aFenceHeldAfterATransientIoErrorIsRedriven() throws Exception {
        writablePages(false);
        assertEquals("500-33", TransactionOperationHelper.commit(clientId).getErrorCode());
        assertTrue(locks.isWriteLockedByCurrentThread(collId));
        assertFalse(TransactionOperationHelper.redriveFenced(clientId), "the disk is still full");
        writablePages(true);

        assertTrue(TransactionOperationHelper.redriveFenced(clientId));

        assertTrue(fencedDocumentExists());
        assertFalse(locks.isWriteLockedByCurrentThread(collId), "the re-drive releases the fence");
        assertNull(clientTracker.getActiveTransaction(clientId));
        assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus(),
                "a re-sent COMMIT after the re-drive learns that it committed");
        assertEquals("409-4", TransactionOperationHelper.commit(clientId).getErrorCode());
    }

    @Test
    public void aCommitRecordLoggedButNotAppliedKeepsTheFence() throws Exception {
        final var spy = Mockito.spy(cache);
        Mockito.doThrow(new UncheckedIOException(
                new LoggedWriteNotAppliedException("admin|transactions", new IOException("No space left on device"))))
                .when(spy).putPkIndexTransaction(ArgumentMatchers.argThat(row -> row != null
                        && row.getValue().endsWith("|" + AdminTransactionEntry.MARKER_LOCAL_COMMIT)));
        TestUtils.setPrivateStaticField(TransactionRecords.class, "cache", spy);

        final var response = TransactionOperationHelper.commit(clientId);

        assertEquals("500-33", response.getErrorCode(), response.getMessage());
        assertNotNull(clientTracker.getActiveTransaction(clientId), "the slice stays fenced");
        assertTrue(locks.isWriteLockedByCurrentThread(collId), "its locks are kept");
        assertTrue(TransactionOperationHelper.holdsRedrivableCommit(clientId));
    }
}
