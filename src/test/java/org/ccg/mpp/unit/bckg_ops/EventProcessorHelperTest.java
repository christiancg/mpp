package org.ccg.mpp.unit.bckg_ops;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.ccg.mpp.bckg_ops.EventProcessorHelper;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.bckg_ops.events.BulkEntityEvent;
import org.ccg.mpp.bckg_ops.events.EntityEvent;
import org.ccg.mpp.bckg_ops.events.Event;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.bckg_ops.events.ScriptRunHistoryEvent;
import org.ccg.mpp.bckg_ops.events.UsageProfileFlushEvent;
import org.ccg.mpp.cache.AccessKind;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.MemoryManagement;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.data.admin.AdminPageEntry;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.IndexHelper;
import org.ccg.mpp.ops.ScriptRunHistory;
import org.ccg.mpp.ops.ScriptRunKind;
import org.ccg.mpp.ops.ScriptRunRecord;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EventProcessorHelperTest {

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    @Test
    public void processProcessEventWithInvalidEventTest() {
        final var event = new Event(EventType.CREATED) {
        };
        Assertions.assertThrows(IllegalStateException.class, () -> EventProcessorHelper.processEvent(event));
    }

    @Test
    public void processScriptRunHistoryEventTest() throws Exception {
        TestUtils.createTestProjectAndCollection();
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptRunHistoryEnabled", true);
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptRunHistoryKinds", "TRIGGER");
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptRunHistoryMaxErrorChars", 2000);
        ScriptRunHistory.reset();
        final var record = new ScriptRunRecord("evt-run-1", ScriptRunKind.TRIGGER, TestGlobals.PROJECT, "job", "proc",
                TestGlobals.COLL, "CREATED", "u1", "u2", System.currentTimeMillis(), 3L, 1, ScriptRunRecord.OUTCOME_OK,
                null, null, null, null, null, false);

        EventProcessorHelper.processEvent(new ScriptRunHistoryEvent(record));

        Assertions.assertEquals(1L, ScriptRunHistory.getRecorded());
        final var stored = IocContainer.get(Cache.class)
                .getWholeCollection(TestGlobals.PROJECT, Globals.SCRIPT_RUNS_COLLECTION_NAME).get("evt-run-1");
        Assertions.assertNotNull(stored);
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptRunHistoryEnabled", false);
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptRunHistoryKinds",
                ConfigKey.SCRIPT_RUN_HISTORY_KINDS.defaultValue());
        ScriptRunHistory.reset();
    }

    @Test
    public void processBulkEntityEventTest() throws IOException, InterruptedException {
        TestUtils.createTestProjectAndCollection();
        final var testObj = new JsonObject();
        testObj.add("myField", "myValue");
        List<DbEntry> insertedEntries = new ArrayList<>();
        insertedEntries.add(DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, testObj));
        final var bulkEntityEvent = new BulkEntityEvent(TestGlobals.PROJECT, TestGlobals.COLL, insertedEntries,
                new ArrayList<>());
        EventProcessorHelper.processEvent(bulkEntityEvent);
        final var cache = IocContainer.get(Cache.class);
        final var pageEntries = cache.getAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL);
        final var totalCount = pageEntries == null
                ? 0
                : pageEntries.stream().mapToInt(AdminPageEntry::getEntryCount).sum();
        Assertions.assertEquals(insertedEntries.size(), totalCount, "Entry count doesn't match");
    }

    @Test
    public void processCreateEntityEventTest() throws IOException, InterruptedException {
        final var entityEvent = new EntityEvent(EventType.CREATED, TestGlobals.PROJECT, TestGlobals.COLL,
                new DbEntry());
        AdminOperationHelper.saveProjectEntry(new AdminProjectEntry(TestGlobals.PROJECT));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.PROJECT, TestGlobals.COLL));
        EventProcessorHelper.processEvent(entityEvent);
        final var collEntry = AdminOperationHelper.getCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL);
        Assertions.assertNotNull(collEntry);
        final var cache = IocContainer.get(Cache.class);
        final var pageEntries = cache.getAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL);
        final var totalCount = pageEntries == null
                ? 0
                : pageEntries.stream().mapToInt(AdminPageEntry::getEntryCount).sum();
        Assertions.assertEquals(1, totalCount, "Entry count should be 1");
    }

    @Test
    public void processCollectionUsageEventUpsertsUsageEntry() throws IOException, InterruptedException {
        AdminOperationHelper.saveProjectEntry(new AdminProjectEntry(TestGlobals.PROJECT));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.PROJECT, TestGlobals.COLL));
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, TestGlobals.PROJECT, TestGlobals.COLL, null);
        EventProcessorHelper.processMaintenance(new UsageProfileFlushEvent());
        final var cache = IocContainer.get(Cache.class);
        final var id = org.ccg.mpp.data.admin.AdminCollectionUsageEntry.buildId(TestGlobals.PROJECT, TestGlobals.COLL,
                "");
        Assertions.assertNotNull(cache.getPkIndexCollectionUsage(id));
    }

    @Test
    public void processCollectionUsageEventIgnoresAdminProject() throws IOException, InterruptedException {
        AdminOperationHelper.flushCollectionUsage(java.util.List.of(
                new org.ccg.mpp.cache.UsageCounter(AccessKind.COLLECTION, org.ccg.mpp.config.Globals.ADMIN_PROJECT_NAME,
                        "projects", null, 1L, System.currentTimeMillis())));
        final var cache = IocContainer.get(Cache.class);
        final var id = org.ccg.mpp.data.admin.AdminCollectionUsageEntry
                .buildId(org.ccg.mpp.config.Globals.ADMIN_PROJECT_NAME, "projects", "");
        Assertions.assertNull(cache.getPkIndexCollectionUsage(id));
    }

    @Test
    public void processEntityEventSkipsVanishedCollection() {
        final var pending = IocContainer.get(PendingIndexWrites.class);
        final var entry = new DbEntry();
        entry.set_id("ghost");
        pending.mark(TestGlobals.PROJECT, TestGlobals.COLL, "ghost");
        final var entityEvent = new EntityEvent(EventType.CREATED, TestGlobals.PROJECT, TestGlobals.COLL, entry);

        Assertions.assertDoesNotThrow(() -> EventProcessorHelper.processEvent(entityEvent));

        Assertions.assertFalse(pending.idsFor(TestGlobals.PROJECT, TestGlobals.COLL).contains("ghost"));
        final var cache = IocContainer.get(Cache.class);
        Assertions.assertNull(cache.getAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    @Test
    public void processEntityEvent_no_orphan_pages_when_collection_dropped_mid_flight()
            throws IOException, InterruptedException {
        // Register the collection so the early guard in processEntityEvent passes...
        AdminOperationHelper.saveProjectEntry(new AdminProjectEntry(TestGlobals.PROJECT));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.PROJECT, TestGlobals.COLL));
        // ...then simulate the concurrent drop completing before baseUpdateEntryCount runs.
        AdminOperationHelper.deleteCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL);
        AdminOperationHelper.deletePageCollections(TestGlobals.PROJECT, TestGlobals.COLL);

        final var entry = new DbEntry();
        entry.set_id("mid-flight-entity");
        final var entityEvent = new EntityEvent(EventType.CREATED, TestGlobals.PROJECT, TestGlobals.COLL, entry);
        Assertions.assertDoesNotThrow(() -> EventProcessorHelper.processEvent(entityEvent));

        final var cache = IocContainer.get(Cache.class);
        Assertions.assertNull(cache.getAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL),
                "No orphan page metadata must be created for a dropped collection");
    }

    @Test
    public void processBulkEntityEvent_no_orphan_pages_when_collection_dropped_mid_flight()
            throws IOException, InterruptedException {
        // Register the collection so the early guard in processBulkEntityEvent passes...
        AdminOperationHelper.saveProjectEntry(new AdminProjectEntry(TestGlobals.PROJECT));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.PROJECT, TestGlobals.COLL));
        // ...then simulate the concurrent drop completing before baseUpdateEntryCount runs.
        AdminOperationHelper.deleteCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL);
        AdminOperationHelper.deletePageCollections(TestGlobals.PROJECT, TestGlobals.COLL);

        final var entry = new DbEntry();
        entry.set_id("mid-flight-bulk");
        final var bulkEvent = new BulkEntityEvent(TestGlobals.PROJECT, TestGlobals.COLL,
                new ArrayList<>(List.of(entry)), new ArrayList<>());
        Assertions.assertDoesNotThrow(() -> EventProcessorHelper.processEvent(bulkEvent));

        final var cache = IocContainer.get(Cache.class);
        Assertions.assertNull(cache.getAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL),
                "No orphan page metadata must be created for a dropped collection");
    }

    private static DbEntry indexedEntry(String collName, String id, int value) {
        final var data = new JsonObject();
        data.add(Globals.PK_FIELD, new JsonString(id));
        data.addProperty("f", value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.PROJECT, collName, data);
        entry.set_id(id);
        return entry;
    }

    private static List<Event> cacheAndBuild(String collName, List<String> ids) {
        final var cache = IocContainer.get(Cache.class);
        final var events = new ArrayList<Event>();
        var value = 1;
        for (final var id : ids) {
            final var entry = indexedEntry(collName, id, value++);
            cache.addEntryToCache(TestGlobals.PROJECT, collName, entry);
            events.add(new EntityEvent(EventType.CREATED, TestGlobals.PROJECT, collName, entry));
        }
        return events;
    }

    private static void indexField(String collName) throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.PROJECT, collName, "f");
        IocContainer.get(Cache.class).getAdminCollectionEntry(TestGlobals.PROJECT, collName).setIndexes(Set.of("f"));
    }

    private void runBatchWithAFailingFirstGroup() throws Exception {
        TestUtils.createTestProjectAndCollection();
        TestUtils.createTestJoinCollection();
        final var batch = new ArrayList<>(cacheAndBuild(TestGlobals.COLL, List.of("a1", "a2")));
        indexField(TestGlobals.COLL);
        indexField(TestGlobals.JOIN_COLL);
        batch.addAll(cacheAndBuild(TestGlobals.JOIN_COLL, List.of("b1", "b2")));

        final var fs = IocContainer.get(FileSystem.class);
        TestUtils.deleteFolder(
                new File(TestUtils.getDbPath(fs), TestGlobals.PROJECT + File.separator + TestGlobals.COLL));

        EventProcessorHelper.processBatch(batch);
    }

    @Test
    public void test_one_failing_group_does_not_skip_the_rest() throws Exception {
        runBatchWithAFailingFirstGroup();

        final var index = IocContainer.get(Cache.class).getFieldIndexAndLoadIfNecessary(TestGlobals.PROJECT,
                TestGlobals.JOIN_COLL, "f", Number.class);
        Assertions.assertNotNull(index, "the second group must still have been indexed");
        final var indexed = index.stream().flatMap(e -> e.getIds().stream()).collect(Collectors.toSet());
        Assertions.assertEquals(Set.of("b1", "b2"), indexed,
                "one transient failure must not abort the rest of the batch");
    }

    @Test
    public void test_a_failing_group_keeps_its_pending_ids() throws Exception {
        final var pending = IocContainer.get(PendingIndexWrites.class);
        pending.mark(TestGlobals.PROJECT, TestGlobals.COLL, List.of("a1", "a2"));

        runBatchWithAFailingFirstGroup();

        Assertions.assertEquals(Set.of("a1", "a2"), pending.idsFor(TestGlobals.PROJECT, TestGlobals.COLL),
                "the rebuild never ran, so the overlay is what keeps index-backed reads exact for those ids;"
                        + " REINDEX is what retires them");
        pending.clearCollection(TestGlobals.PROJECT, TestGlobals.COLL);
    }

    private static EntityEvent indexedEvent() throws Exception {
        TestUtils.createTestProjectAndCollection();
        IndexHelper.createIndex(TestGlobals.PROJECT, TestGlobals.COLL, "f");
        IocContainer.get(Cache.class).getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL)
                .setIndexes(Set.of("f"));
        final var data = new JsonObject();
        data.add(Globals.PK_FIELD, new JsonString("a1"));
        data.addProperty("f", 1);
        final var entry = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, data);
        entry.set_id("a1");
        IocContainer.get(Cache.class).addEntryToCache(TestGlobals.PROJECT, TestGlobals.COLL, entry);
        return new EntityEvent(EventType.CREATED, TestGlobals.PROJECT, TestGlobals.COLL, entry);
    }

    @Test
    public void singleEventBatchRestoresTheInterruptAndReturnsTheEventDeferred() throws Exception {
        final var event = indexedEvent();
        Thread.currentThread().interrupt();

        final var deferred = EventProcessorHelper.processBatch(List.of(event));

        Assertions.assertTrue(Thread.interrupted());
        Assertions.assertEquals(List.of(event), deferred);
    }

    @Test
    public void multiEventBatchStillRestoresTheInterrupt() throws Exception {
        final var event = indexedEvent();
        Thread.currentThread().interrupt();

        EventProcessorHelper.processBatch(List.of(event, event));

        Assertions.assertTrue(Thread.interrupted());
    }
}
