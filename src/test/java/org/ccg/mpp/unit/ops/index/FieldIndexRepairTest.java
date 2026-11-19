package org.ccg.mpp.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.ccg.mpp.bckg_ops.BackgroundLanes;
import org.ccg.mpp.bckg_ops.BackgroundTaskManager;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.bckg_ops.events.IndexRepairEvent;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.IndexHelper;
import org.ccg.mpp.ops.SaveOperationHelper;
import org.ccg.mpp.ops.index.IndexRepairPass;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FieldIndexRepairTest {
    private static final String FIELD = "tag";
    private static final String SLOT_CLASS = "org.ccg.mpp.ops.index.FieldIndexSlot";
    private static final int DOCS = 2 * Globals.REPAIR_PASS_CHUNK + 808;
    private static final int CHUNKS = 3;

    private final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem realFs = IocContainer.get(FileSystem.class);
    private final ResourceLocking realLocks = IocContainer.get(ResourceLocking.class);
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private final BackgroundTaskManager realTaskManager = IocContainer.get(BackgroundTaskManager.class);
    private Long savedMaxMemory;
    private BackgroundTaskManager stubTaskManager;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        stubTaskManager = new BackgroundTaskManager();
        TestUtils.setPrivateStaticField(IndexRepairPass.class, "taskManager", stubTaskManager);
        saveAll("before");
        IndexHelper.createIndex(TestGlobals.PROJECT, TestGlobals.COLL, FIELD);
        cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL).setIndexes(Set.of(FIELD));
        saveAll("after");
        pendingIndexWrites.clearCollection(TestGlobals.PROJECT, TestGlobals.COLL);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", Globals.CACHE_DISABLED);
        cache.evictFieldIndexAllTypes(TestGlobals.PROJECT, TestGlobals.COLL, FIELD);
    }

    @AfterEach
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void tearDown() throws Exception {
        Thread.interrupted();
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", realFs);
        TestUtils.setPrivateStaticField(IndexRepairPass.class, "rl", realLocks);
        TestUtils.setPrivateStaticField(IndexRepairPass.class, "taskManager", realTaskManager);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        pendingIndexWrites.clearCollection(TestGlobals.PROJECT, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonBaseElement valueOf(int i, String phase) {
        return i % 3 == 0
                ? new JsonNumber(i % 50 + ("after".equals(phase) ? 1000 : 0))
                : new JsonString(phase + "-" + i % 70);
    }

    private void saveAll(String phase) throws Exception {
        final var objects = new ArrayList<JsonObject>(DOCS);
        for (var i = 0; i < DOCS; i++) {
            final var doc = new JsonObject();
            doc.add(Globals.PK_FIELD, new JsonString("d" + i));
            doc.add(FIELD, valueOf(i, phase));
            objects.add(doc);
        }
        final var request = new BulkSaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setObjects(objects);
        realLocks.lock(TestGlobals.PROJECT, TestGlobals.COLL);
        try {
            SaveOperationHelper.executeBulkSave(request);
        } finally {
            realLocks.release(TestGlobals.PROJECT, TestGlobals.COLL);
        }
        TestUtils.discardQueuedBackgroundEvents(realTaskManager);
    }

    private static List<String> everyId() {
        final var ids = new ArrayList<String>(DOCS);
        for (var i = 0; i < DOCS; i++) {
            ids.add("d" + i);
        }
        return ids;
    }

    private IndexRepairEvent markedPass() {
        final var ids = everyId();
        return new IndexRepairEvent(TestGlobals.PROJECT, TestGlobals.COLL, ids, 0L,
                pendingIndexWrites.markRecovered(TestGlobals.PROJECT, TestGlobals.COLL, ids));
    }

    private void assertIndexMatchesTheDocuments() throws Exception {
        assertIndexMatchesTheDocuments(Map.of());
    }

    private void assertIndexMatchesTheDocuments(Map<String, JsonBaseElement> changed) throws Exception {
        final var documents = new ArrayList<DbEntry>(DOCS);
        for (var i = 0; i < DOCS; i++) {
            final var object = new JsonObject();
            object.add(Globals.PK_FIELD, new JsonString("d" + i));
            object.add(FIELD, changed.getOrDefault("d" + i, valueOf(i, "after")));
            documents.add(DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, object));
        }
        assertEquals(FieldIndexBatchSupport.legacyBuildSnapshot(documents, FIELD),
                FieldIndexBatchSupport.snapshot(FIELD));
    }

    private FileSystem spyTheSlots() throws Exception {
        final var spied = spy(realFs);
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", spied);
        return spied;
    }

    private ResourceLocking spyTheLocks() throws Exception {
        final var spied = spy(realLocks);
        TestUtils.setPrivateStaticField(IndexRepairPass.class, "rl", spied);
        return spied;
    }

    private List<IndexRepairEvent> resubmitted() throws Exception {
        final var lanes = TestUtils.getPrivateField(stubTaskManager, "lanes", BackgroundLanes.class);
        final Collection<?> queue = TestUtils.getPrivateField(lanes, "maintenance", Collection.class);
        return queue.stream().map(IndexRepairEvent.class::cast).toList();
    }

    @Test
    public void repairLoadsEachTypeOnce() throws Exception {
        final var pass = markedPass();
        final var spied = spyTheSlots();

        IndexRepairPass.run(pass);

        verify(spied, times(1)).readWholeFieldIndexFiles(anyString(), anyString(), eq(FIELD), eq(String.class));
        verify(spied, times(1)).readWholeFieldIndexFiles(anyString(), anyString(), eq(FIELD), eq(Number.class));
        assertIndexMatchesTheDocuments();
        assertTrue(pendingIndexWrites.idsFor(TestGlobals.PROJECT, TestGlobals.COLL).isEmpty());
    }

    @Test
    public void repairPassChunksAndYields() throws Exception {
        final var pass = markedPass();
        final var locks = spyTheLocks();
        final var writerGotIn = new AtomicInteger();
        doAnswer(invocation -> {
            final var acquired = CompletableFuture.supplyAsync(() -> {
                try {
                    final var got = realLocks.tryLockWrite(TestGlobals.PROJECT, TestGlobals.COLL, 2_000L);
                    if (got) {
                        realLocks.release(TestGlobals.PROJECT, TestGlobals.COLL);
                    }
                    return got;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }).get();
            if (acquired) {
                writerGotIn.incrementAndGet();
            }
            return invocation.callRealMethod();
        }).when(locks).tryLockRead(eq(TestGlobals.PROJECT), eq(TestGlobals.COLL), anyLong());

        IndexRepairPass.run(pass);

        verify(locks, times(CHUNKS)).tryLockRead(eq(TestGlobals.PROJECT), eq(TestGlobals.COLL), anyLong());
        assertEquals(CHUNKS, writerGotIn.get(), "a writer gets the collection between every two chunks");
        assertIndexMatchesTheDocuments();
    }

    @Test
    public void aWriteMaintainedBetweenTwoChunksIsNotFoldedAway() throws Exception {
        final var pass = markedPass();
        final var locks = spyTheLocks();
        final var calls = new AtomicInteger();
        final var concurrent = new JsonString("written-between-chunks");
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                saveD1(concurrent);
                IndexHelper.bulkUpdateIndexes(TestGlobals.PROJECT, TestGlobals.COLL, List.of("d1"));
            }
            return invocation.callRealMethod();
        }).when(locks).tryLockRead(eq(TestGlobals.PROJECT), eq(TestGlobals.COLL), anyLong());

        IndexRepairPass.run(pass);

        assertIndexMatchesTheDocuments(Map.of("d1", concurrent));
    }

    private void saveD1(JsonBaseElement value) throws Exception {
        final var id = "d1";
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add(FIELD, value);
        final var request = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.set_id(id);
        request.setObject(object);
        realLocks.lock(TestGlobals.PROJECT, TestGlobals.COLL);
        try {
            SaveOperationHelper.executeSave(request);
        } finally {
            realLocks.release(TestGlobals.PROJECT, TestGlobals.COLL);
        }
    }

    @Test
    public void anInterruptedPassLeavesItsIdsPending() throws Exception {
        final var pass = markedPass();
        final var locks = spyTheLocks();
        final var calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                Thread.currentThread().interrupt();
            }
            return invocation.callRealMethod();
        }).when(locks).tryLockRead(eq(TestGlobals.PROJECT), eq(TestGlobals.COLL), anyLong());

        assertThrows(InterruptedException.class, () -> IndexRepairPass.run(pass));

        assertEquals(DOCS, pendingIndexWrites.idsFor(TestGlobals.PROJECT, TestGlobals.COLL).size(),
                "the next start re-queues what the pass did not finish");
    }

    @Test
    public void aBusyCollectionResubmitsTheRestOfThePass() throws Exception {
        final var pass = markedPass();
        final var locks = spyTheLocks();
        final var calls = new AtomicInteger();
        doAnswer(invocation -> calls.incrementAndGet() == 2 ? Boolean.FALSE : invocation.callRealMethod()).when(locks)
                .tryLockRead(eq(TestGlobals.PROJECT), eq(TestGlobals.COLL), anyLong());

        IndexRepairPass.run(pass);

        final var rest = resubmitted();
        assertEquals(1, rest.size());
        assertEquals(FIELD, rest.getFirst().getResumeField());
        assertEquals(Globals.REPAIR_PASS_CHUNK, rest.getFirst().getResumeAt());
        assertEquals(DOCS, pendingIndexWrites.idsFor(TestGlobals.PROJECT, TestGlobals.COLL).size());
        IndexRepairPass.run(rest.getFirst());
        assertIndexMatchesTheDocuments();
        assertTrue(pendingIndexWrites.idsFor(TestGlobals.PROJECT, TestGlobals.COLL).isEmpty());
    }

    @Test
    public void aBusyPassDuringTheDrainDoesNotLeaveAnUncleanMarker() throws Exception {
        final var pass = markedPass();
        final var locks = spyTheLocks();
        final var calls = new AtomicInteger();
        doAnswer(invocation -> calls.incrementAndGet() == 2 ? Boolean.FALSE : invocation.callRealMethod()).when(locks)
                .tryLockRead(eq(TestGlobals.PROJECT), eq(TestGlobals.COLL), anyLong());
        TestUtils.setPrivateField(stubTaskManager, "draining", true);

        IndexRepairPass.run(pass);
        final var rest = resubmitted();
        assertEquals(1, rest.size(), "the drain waits for the rest of the pass instead of dropping it");
        IndexRepairPass.run(rest.getFirst());

        assertTrue(pendingIndexWrites.idsFor(TestGlobals.PROJECT, TestGlobals.COLL).isEmpty());
        assertTrue(realFs.indexMarkers().retainUncleanStops().isEmpty());
    }

    @Test
    public void aPassForADroppedCollectionOnlyClearsItsMarks() throws Exception {
        final var ids = List.of("x", "y");
        final var pass = new IndexRepairEvent(TestGlobals.PROJECT, "droppedColl", ids, 0L,
                pendingIndexWrites.mark(TestGlobals.PROJECT, "droppedColl", ids));

        IndexRepairPass.run(pass);

        assertTrue(pendingIndexWrites.idsFor(TestGlobals.PROJECT, "droppedColl").isEmpty());
        pendingIndexWrites.clearCollection(TestGlobals.PROJECT, "droppedColl");
    }

    @Test
    public void aFieldAlreadyRepairedIsSkipped() throws Exception {
        final var ids = everyId();
        final var pass = new IndexRepairEvent(TestGlobals.PROJECT, TestGlobals.COLL, ids, 0L,
                pendingIndexWrites.markRecovered(TestGlobals.PROJECT, TestGlobals.COLL, ids), Set.of(FIELD), null, 0);
        final var spied = spyTheSlots();

        IndexRepairPass.run(pass);

        verify(spied, times(0)).readWholeFieldIndexFiles(anyString(), anyString(), eq(FIELD), eq(String.class));
        assertTrue(pendingIndexWrites.idsFor(TestGlobals.PROJECT, TestGlobals.COLL).isEmpty());
    }

    @Test
    public void eventsCompareByTheirWork() {
        final var first = new IndexRepairEvent(TestGlobals.PROJECT, TestGlobals.COLL, List.of("a"), 1L, 2L);
        final var same = new IndexRepairEvent(TestGlobals.PROJECT, TestGlobals.COLL, List.of("a"), 1L, 2L);
        final var other = new IndexRepairEvent(TestGlobals.PROJECT, TestGlobals.COLL, List.of("a"), 1L, 2L, Set.of(),
                FIELD, 4096);

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, other);
        assertTrue(other.toString().contains("resumeAt=4096"));
    }
}
