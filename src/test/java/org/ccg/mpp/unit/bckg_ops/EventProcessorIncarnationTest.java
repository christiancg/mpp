package org.ccg.mpp.unit.bckg_ops;

import java.io.IOException;
import java.util.List;
import org.ccg.mpp.bckg_ops.EventProcessorHelper;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.bckg_ops.events.BulkEntityEvent;
import org.ccg.mpp.bckg_ops.events.EntityEvent;
import org.ccg.mpp.bckg_ops.events.EntityRef;
import org.ccg.mpp.bckg_ops.events.Event;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.admin.AdminPageEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.admin.CollectionIncarnation;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EventProcessorIncarnationTest {
    private static final long CURRENT = 200L;
    private static final long DROPPED = 100L;

    private Cache cache;
    private PendingIndexWrites pendingIndexWrites;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        cache = IocContainer.get(Cache.class);
        pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
        cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL).setIncarnation(CURRENT);
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private static DbEntry entryOnPage(String id, long page) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("payload", new JsonString("some bytes"));
        final var entry = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, object);
        entry.set_id(id);
        entry.setPage(page);
        return entry;
    }

    private AdminPageEntry page(long number) {
        final var pages = cache.getAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL);
        if (pages == null) {
            return null;
        }
        return pages.stream().filter(p -> p.getPage() == number).findFirst().orElse(null);
    }

    private AdminPageEntry firstPage() {
        final var found = page(0);
        Assertions.assertNotNull(found, "page 0 has no row");
        return found;
    }

    private void seedLivePage() throws IOException, InterruptedException {
        EventProcessorHelper.processEvent(new BulkEntityEvent(TestGlobals.PROJECT, TestGlobals.COLL,
                List.of(entryOnPage("live", 0)), List.of(), CURRENT));
    }

    private static EntityEvent event(EventType type, DbEntry entry, long incarnation) {
        return new EntityEvent(type, TestGlobals.PROJECT, TestGlobals.COLL, entry, incarnation);
    }

    @Test
    public void test_stale_deleted_event_does_not_touch_a_recreated_collections_pages() throws Exception {
        seedLivePage();
        final var before = firstPage();
        final var countBefore = before.getEntryCount();
        final var sizeBefore = before.getPageSize();

        EventProcessorHelper.processEvent(event(EventType.DELETED, entryOnPage("gone", 0), DROPPED));

        Assertions.assertEquals(countBefore, firstPage().getEntryCount());
        Assertions.assertEquals(sizeBefore, firstPage().getPageSize());
    }

    @Test
    public void test_stale_created_event_does_not_insert_a_phantom_page() throws Exception {
        EventProcessorHelper.processEvent(event(EventType.CREATED, entryOnPage("gone", 5), DROPPED));

        Assertions.assertNull(page(5));
    }

    @Test
    public void test_stale_bulk_event_is_skipped_and_its_pending_marks_cleared() throws Exception {
        pendingIndexWrites.mark(TestGlobals.PROJECT, TestGlobals.COLL, "gone");

        EventProcessorHelper.processEvent(new BulkEntityEvent(TestGlobals.PROJECT, TestGlobals.COLL,
                List.of(entryOnPage("gone", 3)), List.of(), DROPPED));

        Assertions.assertNull(page(3));
        Assertions.assertFalse(pendingIndexWrites.idsFor(TestGlobals.PROJECT, TestGlobals.COLL).contains("gone"));
    }

    @Test
    public void test_stale_events_in_a_mixed_batch_are_skipped_while_current_ones_apply() throws Exception {
        final List<Event> batch = List.of(event(EventType.CREATED, entryOnPage("gone", 7), DROPPED),
                event(EventType.CREATED, entryOnPage("kept", 8), CURRENT));

        EventProcessorHelper.processBatch(batch);

        Assertions.assertNull(page(7));
        Assertions.assertNotNull(page(8));
    }

    @Test
    public void test_event_with_matching_incarnation_is_processed() throws Exception {
        seedLivePage();
        final var countBefore = firstPage().getEntryCount();
        final var live = entryOnPage("live", 0);
        cache.updatePageSizeInMemory(TestGlobals.PROJECT, TestGlobals.COLL, 0L, -live.byteSize(), -1);

        EventProcessorHelper.processEvent(event(EventType.DELETED, live, CURRENT));

        Assertions.assertEquals(countBefore - 1, firstPage().getEntryCount(),
                "the delete's delta was applied on the write path, so the event does not apply it again");
    }

    @Test
    public void test_zero_incarnation_on_either_side_is_processed() throws Exception {
        EventProcessorHelper.processEvent(event(EventType.CREATED, entryOnPage("unstamped", 4), 0L));
        Assertions.assertNotNull(page(4));

        cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL).setIncarnation(0L);
        EventProcessorHelper.processEvent(event(EventType.CREATED, entryOnPage("legacy", 6), DROPPED));
        Assertions.assertNotNull(page(6));
    }

    @Test
    public void test_entry_count_refuses_an_incarnation_that_changed_under_the_lock() throws Exception {
        AdminOperationHelper.updateEntryCount(TestGlobals.PROJECT, TestGlobals.COLL, EventType.CREATED,
                EntityRef.of(entryOnPage("raced", 9)), DROPPED);
        AdminOperationHelper.bulkUpdateEntryCount(TestGlobals.PROJECT, TestGlobals.COLL, EventType.CREATED,
                List.of(EntityRef.of(entryOnPage("raced-bulk", 10))), DROPPED);

        Assertions.assertNull(page(9));
        Assertions.assertNull(page(10));
    }

    @Test
    public void test_a_stale_group_creates_no_page_row() throws Exception {
        final var batch = List.<Event>of(event(EventType.CREATED, entryOnPage("stale-a", 11), DROPPED),
                event(EventType.CREATED, entryOnPage("stale-b", 11), DROPPED));

        EventProcessorHelper.processBatch(batch);

        Assertions.assertNull(page(11));
    }

    @Test
    public void test_a_booking_under_a_changed_incarnation_creates_no_row() throws Exception {
        AdminOperationHelper.bookWritePathPages(TestGlobals.PROJECT, TestGlobals.COLL,
                List.of(EntityRef.of(entryOnPage("raced-group", 12))), List.of(), DROPPED);

        Assertions.assertNull(page(12));
    }

    @Test
    public void test_current_incarnation_reads_the_registered_entry() {
        Assertions.assertEquals(CURRENT, CollectionIncarnation.current(TestGlobals.PROJECT, TestGlobals.COLL));
        Assertions.assertEquals(0L, CollectionIncarnation.current(TestGlobals.PROJECT, "neverCreated"));
        Assertions.assertFalse(CollectionIncarnation.isCurrent(TestGlobals.PROJECT, "neverCreated", 0L));
    }
}
