package org.ccg.mpp.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.stream.Stream;
import org.ccg.mpp.bckg_ops.EventProcessorHelper;
import org.ccg.mpp.bckg_ops.events.BulkEntityEvent;
import org.ccg.mpp.bckg_ops.events.EntityEvent;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.admin.AdminPageEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EntityEventIdsOnlyTest {
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private static DbEntry entryOnPage(String id, long page) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("payload", new JsonString("some bytes"));
        final var entry = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, object);
        entry.setPage(page);
        return entry;
    }

    private boolean hasPageRow(long number) {
        final var pages = cache.getAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL);
        return pages != null && pages.stream().anyMatch(p -> p.getPage() == number);
    }

    private AdminPageEntry page(long number) {
        final var pages = cache.getAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL);
        assertNotNull(pages, "the collection has page rows");
        return pages.stream().filter(p -> p.getPage() == number).findFirst()
                .orElseThrow(() -> new AssertionError("no page row for page " + number));
    }

    private static boolean holdsADocument(Field field) {
        if (DbEntry.class.isAssignableFrom(field.getType())) {
            return true;
        }
        return field.getGenericType() instanceof ParameterizedType generic
                && Stream.of(generic.getActualTypeArguments()).anyMatch(argument -> argument == DbEntry.class);
    }

    @Test
    public void eventsHoldNoDocument() {
        for (final var type : List.of(EntityEvent.class, BulkEntityEvent.class)) {
            for (Class<?> level = type; level != null; level = level.getSuperclass()) {
                for (final var field : level.getDeclaredFields()) {
                    assertFalse(holdsADocument(field), level.getSimpleName() + "." + field.getName()
                            + " keeps a document alive in the queue; workers re-read it by id");
                }
            }
        }
    }

    @Test
    public void pageDeltaUsesRecordedPage() throws Exception {
        final var entry = entryOnPage("moved", 3);
        final var event = new EntityEvent(EventType.CREATED, TestGlobals.PROJECT, TestGlobals.COLL, entry);
        entry.setPage(7);

        EventProcessorHelper.processEvent(event);

        assertTrue(hasPageRow(3), "the delta belongs to the page the write landed on");
        assertFalse(hasPageRow(7), "no row appears for a page the event never named");
    }

    @Test
    public void failedRelocationDeltaLandsOnTheOriginalPage() throws Exception {
        final var original = entryOnPage("relocated", 0);
        EventProcessorHelper
                .processEvent(new BulkEntityEvent(TestGlobals.PROJECT, TestGlobals.COLL, List.of(original), List.of()));
        final var before = page(0);
        assertNotNull(before);
        final var countBefore = before.getEntryCount();
        final var deleted = new EntityEvent(EventType.DELETED, TestGlobals.PROJECT, TestGlobals.COLL, original);
        cache.updatePageSizeInMemory(TestGlobals.PROJECT, TestGlobals.COLL, 0L, -original.byteSize(), -1);
        original.setPage(4);
        final var restored = new EntityEvent(EventType.CREATED, TestGlobals.PROJECT, TestGlobals.COLL, original);

        EventProcessorHelper.processEvent(deleted);
        EventProcessorHelper.processEvent(restored);

        assertEquals(countBefore - 1, page(0).getEntryCount(),
                "the delete's write-path delta left the page it was removed from, and its event did not apply it again");
        assertEquals(1, page(4).getEntryCount(), "the restore lands on the page it was re-inserted into");
    }
}
