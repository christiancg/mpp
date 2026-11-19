package org.ccg.mpp.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CollectionIdentifiersTest {
    private static final String PROJECT = "idMemoProject";
    private static final String CAP_PROJECT = "idMemoCapProject";
    private static final String THREADS_PROJECT = "idMemoThreadsProject";
    private static final int PAST_THE_CAP = 9_000;
    private static final int THREADS = 8;
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        cache.evictProject(PROJECT);
        cache.evictProject(CAP_PROJECT);
        cache.evictProject(THREADS_PROJECT);
        TestUtils.standardTearDown();
    }

    private static String concatenated(String projectName, String collName) {
        return projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }

    @Test
    public void the_identifier_equals_the_concatenation_and_is_remembered() {
        final var first = Cache.getCollectionIdentifier(PROJECT, "orders");
        final var again = Cache.getCollectionIdentifier(PROJECT, "orders");

        assertEquals(concatenated(PROJECT, "orders"), first);
        assertSame(first, again);
        assertEquals(concatenated(PROJECT, "items"), Cache.getCollectionIdentifier(PROJECT, "items"));
    }

    @Test
    public void canonical_names_are_the_first_seen_instances() {
        final var db = new String(PROJECT.toCharArray());
        final var coll = new String("names".toCharArray());
        final var first = Cache.canonicalNames(db, coll);

        final var again = Cache.canonicalNames(new String(PROJECT.toCharArray()), new String("names".toCharArray()));

        assertSame(db, again.projectName());
        assertSame(coll, again.collName());
        assertSame(first.identifier(), Cache.getCollectionIdentifier(PROJECT, "names"));
    }

    @Test
    public void evicting_a_collection_forgets_its_canonical_names() {
        final var coll = new String("forgotten".toCharArray());
        Cache.canonicalNames(PROJECT, coll);

        cache.evictCollection(PROJECT, "forgotten");

        assertNotSame(coll, Cache.canonicalNames(PROJECT, new String("forgotten".toCharArray())).collName());
    }

    @Test
    public void past_the_cap_the_memo_starts_over_and_keeps_canonicalising() {
        final var early = Cache.getCollectionIdentifier(CAP_PROJECT, "c0");
        for (var i = 1; i < PAST_THE_CAP; i++) {
            Cache.getCollectionIdentifier(CAP_PROJECT, "c" + i);
        }
        final var coll = new String("late".toCharArray());

        final var first = Cache.canonicalNames(CAP_PROJECT, coll);
        final var again = Cache.canonicalNames(CAP_PROJECT, new String("late".toCharArray()));

        assertSame(coll, again.collName(), "a name first seen after the memo filled is still canonicalised");
        assertSame(first.identifier(), again.identifier());
        assertNotSame(early, Cache.getCollectionIdentifier(CAP_PROJECT, "c0"),
                "a name remembered before the memo filled ages out with that generation");
        assertEquals(early, Cache.getCollectionIdentifier(CAP_PROJECT, "c0"));
    }

    @Test
    public void a_dead_name_left_by_a_late_lookup_ages_out_instead_of_filling_the_memo_for_good() {
        for (var round = 0; round < 3; round++) {
            for (var i = 0; i < PAST_THE_CAP; i++) {
                final var dropped = "dead" + round + "_" + i;
                Cache.getCollectionIdentifier(CAP_PROJECT, dropped);
                cache.evictCollection(CAP_PROJECT, dropped);
                Cache.getCollectionIdentifier(CAP_PROJECT, dropped);
            }
        }
        final var live = new String("live".toCharArray());
        Cache.canonicalNames(CAP_PROJECT, live);

        assertSame(live, Cache.canonicalNames(CAP_PROJECT, new String("live".toCharArray())).collName());
    }

    @Test
    public void a_null_name_keeps_the_concatenation_behaviour() {
        assertEquals(concatenated(null, "orders"), Cache.getCollectionIdentifier(null, "orders"));
        assertEquals(concatenated(PROJECT, null), Cache.getCollectionIdentifier(PROJECT, null));
        assertEquals(concatenated(null, null), Cache.getCollectionIdentifier(null, null));
    }

    @Test
    public void evicting_a_collection_forgets_only_its_identifier() {
        final var orders = Cache.getCollectionIdentifier(PROJECT, "orders");
        final var items = Cache.getCollectionIdentifier(PROJECT, "items");

        cache.evictCollection(PROJECT, "orders");

        assertNotSame(orders, Cache.getCollectionIdentifier(PROJECT, "orders"));
        assertEquals(orders, Cache.getCollectionIdentifier(PROJECT, "orders"));
        assertSame(items, Cache.getCollectionIdentifier(PROJECT, "items"));
    }

    @Test
    public void evicting_a_project_forgets_every_identifier_in_it() {
        final var orders = Cache.getCollectionIdentifier(PROJECT, "orders");
        final var items = Cache.getCollectionIdentifier(PROJECT, "items");

        cache.evictProject(PROJECT);

        assertNotSame(orders, Cache.getCollectionIdentifier(PROJECT, "orders"));
        assertNotSame(items, Cache.getCollectionIdentifier(PROJECT, "items"));
    }

    @Test
    public void past_the_cap_identifiers_are_still_correct_and_the_latest_ones_are_remembered() {
        String last = null;
        for (var i = 0; i < PAST_THE_CAP; i++) {
            last = Cache.getCollectionIdentifier(CAP_PROJECT, "c" + i);
            assertEquals(concatenated(CAP_PROJECT, "c" + i), last);
        }

        assertSame(last, Cache.getCollectionIdentifier(CAP_PROJECT, "c" + (PAST_THE_CAP - 1)));

        cache.evictProject(CAP_PROJECT);
        final var room = Cache.getCollectionIdentifier(CAP_PROJECT, "fresh");
        assertSame(room, Cache.getCollectionIdentifier(CAP_PROJECT, "fresh"));
    }

    @Test
    public void concurrent_callers_always_get_the_right_identifier() throws Exception {
        final var wrong = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        try (var pool = Executors.newFixedThreadPool(THREADS)) {
            final var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (var thread = 0; thread < THREADS; thread++) {
                tasks.add(pool.submit(() -> {
                    for (var round = 0; round < 5_000; round++) {
                        final var collection = "c" + round % 100;
                        final var identifier = Cache.getCollectionIdentifier(THREADS_PROJECT, collection);
                        if (!identifier.equals(concatenated(THREADS_PROJECT, collection))) {
                            wrong.add(identifier);
                        }
                    }
                }));
            }
            for (final var task : tasks) {
                task.get(60, TimeUnit.SECONDS);
            }
        }

        assertEquals(0, wrong.size(), wrong.toString());
    }
}
