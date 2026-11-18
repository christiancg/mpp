package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.listen.ListenManager;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.TriggerDeadLetterCap;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.DropDatabaseRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.ccg.mpp.utils.ReflectionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DroppedCollectionsTest {
    private static final String KEY = TestGlobals.DB + "|" + TestGlobals.COLL;

    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        listenManager.stopWorkers();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private Map<String, ?> applyStates() throws ReflectiveOperationException {
        final var tracker = TestUtils.getPrivateField(listenManager, "applyTracker", Object.class);
        return TestUtils.getPrivateField(tracker, "states", new ReflectionUtils.TypeToken<>() {
        });
    }

    private Map<String, ?> collectionToListens() throws ReflectiveOperationException {
        return TestUtils.getPrivateField(listenManager, "collectionToListens", new ReflectionUtils.TypeToken<>() {
        });
    }

    private UUID listen() {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of());
        return listenManager.register(UUID.randomUUID(), request, "hash");
    }

    private void applyOnce() {
        listenManager.openBatch(List.of(KEY)).close();
    }

    @Test
    public void dropForgetsApplyStateAndEmptySets() throws Exception {
        final var stopped = listen();
        assertTrue(listenManager.unregister(stopped));
        assertFalse(collectionToListens().containsKey(KEY), "the last listen leaving a key removes its set");

        listen();
        applyOnce();
        assertTrue(applyStates().containsKey(KEY));

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.DB, TestGlobals.COLL)).getStatus());

        assertFalse(applyStates().containsKey(KEY), "a dropped collection keeps no apply state");
        assertFalse(collectionToListens().containsKey(KEY), "the ended listen leaves no empty set behind");
    }

    @Test
    public void dropDatabaseForgetsEveryApplyStateOfIt() throws Exception {
        applyOnce();
        listenManager.openBatch(List.of("otherdb|" + TestGlobals.COLL)).close();

        assertEquals(OperationStatus.OK, processor.processMessage(new DropDatabaseRequest(TestGlobals.DB)).getStatus());

        assertFalse(applyStates().containsKey(KEY));
        assertTrue(applyStates().containsKey("otherdb|" + TestGlobals.COLL));
    }

    @Test
    public void dropDatabaseEvictsProceduresLock() throws Exception {
        final var proceduresKey = Cache.getCollectionIdentifier(TestGlobals.DB, Globals.PROCEDURES_FOLDER);
        locks.lock(TestGlobals.DB, Globals.PROCEDURES_FOLDER);
        locks.release(TestGlobals.DB, Globals.PROCEDURES_FOLDER);
        final Map<String, ?> lockMap = TestUtils.getPrivateField(locks, "locks", new ReflectionUtils.TypeToken<>() {
        });
        assertTrue(lockMap.containsKey(proceduresKey));
        final var remembered = Cache.canonicalNames(TestGlobals.DB, Globals.PROCEDURES_FOLDER);

        assertEquals(OperationStatus.OK, processor.processMessage(new DropDatabaseRequest(TestGlobals.DB)).getStatus());

        assertFalse(lockMap.containsKey(proceduresKey), "the dropped database's procedures lock is evicted");
        assertNotSame(remembered, Cache.canonicalNames(TestGlobals.DB, Globals.PROCEDURES_FOLDER),
                "evicting the lock looks the identifier up again, so the drop forgets it afterwards");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void dropDatabaseForgetsItsDeadLetterOverCapState() throws Exception {
        final Set<String> overCap = TestUtils.getPrivateStaticField(TriggerDeadLetterCap.class, "overCap", Set.class);
        overCap.add(TestGlobals.DB);
        overCap.add("otherdb");
        try {
            assertEquals(OperationStatus.OK,
                    processor.processMessage(new DropDatabaseRequest(TestGlobals.DB)).getStatus());

            assertFalse(overCap.contains(TestGlobals.DB), "a dropped database leaves no over-cap state behind");
            assertTrue(overCap.contains("otherdb"));
        } finally {
            overCap.clear();
        }
    }
}
