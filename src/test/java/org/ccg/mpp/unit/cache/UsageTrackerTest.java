package org.ccg.mpp.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.ccg.mpp.cache.AccessKind;
import org.ccg.mpp.cache.UsageCounter;
import org.ccg.mpp.cache.UsageTracker;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class UsageTrackerTest {
    private static final String PROJECT = "usageProject";
    private static final String COLL = "usageColl";
    private static final int THREADS = 6;
    private static final int ACCESSES_PER_THREAD = 20_000;
    private static final long TOTAL_ACCESSES = (long) THREADS * ACCESSES_PER_THREAD;
    private UsageTracker tracker;

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
        tracker = new UsageTracker();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    @Test
    public void an_access_creates_one_counter_and_every_later_access_counts_on_it() {
        final var before = System.currentTimeMillis();

        tracker.recordAccess(AccessKind.COLLECTION, PROJECT, COLL, null);
        final var first = tracker.getCounter(AccessKind.COLLECTION, PROJECT, COLL, null);
        tracker.recordAccess(AccessKind.COLLECTION, PROJECT, COLL, null);
        tracker.recordAccess(AccessKind.COLLECTION, PROJECT, COLL, "");

        assertNotNull(first);
        assertSame(first, tracker.getCounter(AccessKind.COLLECTION, PROJECT, COLL, ""));
        assertEquals(3, first.getAccessCount());
        assertTrue(first.getLastAccessMillis() >= before);
        assertEquals("", first.indexKey());
    }

    @Test
    public void kinds_collections_and_index_keys_each_get_their_own_counter() {
        tracker.recordAccess(AccessKind.COLLECTION, PROJECT, COLL, null);
        tracker.recordAccess(AccessKind.PK_INDEX, PROJECT, COLL, null);
        tracker.recordAccess(AccessKind.COLLECTION, PROJECT, "other", null);
        tracker.recordAccess(AccessKind.FIELD_INDEX, PROJECT, COLL, "price");
        tracker.recordAccess(AccessKind.FIELD_INDEX, PROJECT, COLL, "name");
        tracker.recordAccess(AccessKind.FIELD_INDEX, PROJECT, COLL, "price");

        assertEquals(1, tracker.getCounter(AccessKind.COLLECTION, PROJECT, COLL, null).getAccessCount());
        assertEquals(1, tracker.getCounter(AccessKind.PK_INDEX, PROJECT, COLL, null).getAccessCount());
        assertEquals(1, tracker.getCounter(AccessKind.COLLECTION, PROJECT, "other", null).getAccessCount());
        assertEquals(2, tracker.getCounter(AccessKind.FIELD_INDEX, PROJECT, COLL, "price").getAccessCount());
        assertEquals(1, tracker.getCounter(AccessKind.FIELD_INDEX, PROJECT, COLL, "name").getAccessCount());
        assertEquals(5, tracker.getCountersSnapshot().size());
    }

    @Test
    public void the_string_keyed_view_holds_the_same_counter() {
        tracker.recordAccess(AccessKind.FIELD_INDEX, PROJECT, COLL, "price");

        final var key = UsageTracker.buildKey(AccessKind.FIELD_INDEX, PROJECT, COLL, "price");

        assertSame(tracker.getCounter(AccessKind.FIELD_INDEX, PROJECT, COLL, "price"),
                tracker.getCountersSnapshot().get(key));
    }

    @Test
    public void admin_accesses_are_not_counted() {
        tracker.recordAccess(AccessKind.COLLECTION, Globals.ADMIN_PROJECT_NAME, "projects", null);

        assertNull(tracker.getCounter(AccessKind.COLLECTION, Globals.ADMIN_PROJECT_NAME, "projects", null));
        assertTrue(tracker.drainDirtyCounters().isEmpty());
    }

    @Test
    public void a_cleared_counter_is_gone_and_the_next_access_starts_a_new_one() {
        tracker.recordAccess(AccessKind.COLLECTION, PROJECT, COLL, null);
        final var old = tracker.getCounter(AccessKind.COLLECTION, PROJECT, COLL, null);

        tracker.clearCounter(AccessKind.COLLECTION, PROJECT, COLL, null);
        assertNull(tracker.getCounter(AccessKind.COLLECTION, PROJECT, COLL, null));
        tracker.recordAccess(AccessKind.COLLECTION, PROJECT, COLL, null);

        final var fresh = tracker.getCounter(AccessKind.COLLECTION, PROJECT, COLL, null);
        assertNotSame(old, fresh);
        assertEquals(1, fresh.getAccessCount());
        assertEquals(1, old.getAccessCount());
    }

    @Test
    public void a_put_counter_replaces_the_one_accesses_count_on() {
        tracker.recordAccess(AccessKind.COLLECTION, PROJECT, COLL, null);
        final var old = tracker.getCounter(AccessKind.COLLECTION, PROJECT, COLL, null);
        final var loaded = new UsageCounter(AccessKind.COLLECTION, PROJECT, COLL, null, 10L, 5L);

        tracker.putCounter(loaded);
        tracker.recordAccess(AccessKind.COLLECTION, PROJECT, COLL, null);

        assertSame(loaded, tracker.getCounter(AccessKind.COLLECTION, PROJECT, COLL, null));
        assertEquals(11, loaded.getAccessCount());
        assertEquals(1, old.getAccessCount());
    }

    @Test
    public void draining_returns_each_touched_counter_once_until_it_is_touched_again() {
        tracker.recordAccess(AccessKind.COLLECTION, PROJECT, COLL, null);
        tracker.recordAccess(AccessKind.COLLECTION, PROJECT, COLL, null);
        tracker.recordAccess(AccessKind.PK_INDEX, PROJECT, COLL, null);

        final var first = tracker.drainDirtyCounters();
        assertEquals(2, first.size());
        assertTrue(tracker.drainDirtyCounters().isEmpty());

        tracker.recordAccess(AccessKind.PK_INDEX, PROJECT, COLL, null);
        final var second = tracker.drainDirtyCounters();
        assertEquals(1, second.size());
        assertSame(tracker.getCounter(AccessKind.PK_INDEX, PROJECT, COLL, null), second.getFirst());
        assertTrue(tracker.drainDirtyCounters().isEmpty());
    }

    @Test
    public void a_cleared_or_replaced_counter_is_not_drained() {
        tracker.recordAccess(AccessKind.COLLECTION, PROJECT, COLL, null);
        tracker.recordAccess(AccessKind.PK_INDEX, PROJECT, COLL, null);

        tracker.clearCounter(AccessKind.COLLECTION, PROJECT, COLL, null);
        tracker.putCounter(new UsageCounter(AccessKind.PK_INDEX, PROJECT, COLL, null, 3L, 1L));

        assertTrue(tracker.drainDirtyCounters().isEmpty());
    }

    @Test
    public void concurrent_accesses_are_all_counted_and_a_racing_drain_never_loses_one() throws Exception {
        final var writing = new AtomicBoolean(true);
        final var lastDrained = new AtomicLong(-1L);
        try (var pool = Executors.newFixedThreadPool(THREADS + 1)) {
            try {
                final var drainer = pool.submit(() -> {
                    while (writing.get()) {
                        tracker.drainDirtyCounters().forEach(counter -> lastDrained.set(counter.getAccessCount()));
                    }
                });
                final var writers = new ArrayList<java.util.concurrent.Future<?>>();
                for (var thread = 0; thread < THREADS; thread++) {
                    writers.add(pool.submit(() -> {
                        for (var access = 0; access < ACCESSES_PER_THREAD; access++) {
                            tracker.recordAccess(AccessKind.COLLECTION, PROJECT, COLL, null);
                        }
                    }));
                }
                for (final var writer : writers) {
                    writer.get(60, TimeUnit.SECONDS);
                }
                writing.set(false);
                drainer.get(60, TimeUnit.SECONDS);
            } finally {
                writing.set(false);
            }
        }

        tracker.drainDirtyCounters().forEach(counter -> lastDrained.set(counter.getAccessCount()));

        assertEquals(TOTAL_ACCESSES, tracker.getCounter(AccessKind.COLLECTION, PROJECT, COLL, null).getAccessCount());
        assertEquals(TOTAL_ACCESSES, lastDrained.get());
        assertTrue(tracker.drainDirtyCounters().isEmpty());
    }

    @Test
    public void test_forget_collection_drops_its_counters() {
        tracker.recordAccess(AccessKind.COLLECTION, PROJECT, COLL, null);
        tracker.recordAccess(AccessKind.FIELD_INDEX, PROJECT, COLL, "price");
        tracker.recordAccess(AccessKind.COLLECTION, PROJECT, "kept", null);
        tracker.recordAccess(AccessKind.COLLECTION, "otherProject", COLL, null);

        tracker.forgetCollection(PROJECT, COLL);

        assertNull(tracker.getCounter(AccessKind.COLLECTION, PROJECT, COLL, null));
        assertNull(tracker.getCounter(AccessKind.FIELD_INDEX, PROJECT, COLL, "price"));
        assertNotNull(tracker.getCounter(AccessKind.COLLECTION, PROJECT, "kept", null));
        final var drained = tracker.drainDirtyCounters();
        assertEquals(2, drained.size(), "a forgotten counter leaves the unflushed set too");
        tracker.recordAccess(AccessKind.COLLECTION, PROJECT, COLL, null);
        assertEquals(1L, tracker.getCounter(AccessKind.COLLECTION, PROJECT, COLL, null).getAccessCount(),
                "a recreated collection starts from a fresh counter");

        tracker.forgetProject(PROJECT);

        assertNull(tracker.getCounter(AccessKind.COLLECTION, PROJECT, COLL, null));
        assertNull(tracker.getCounter(AccessKind.COLLECTION, PROJECT, "kept", null));
        assertNotNull(tracker.getCounter(AccessKind.COLLECTION, "otherProject", COLL, null));
    }
}
