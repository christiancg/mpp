package org.ccg.mpp.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.IndexHelper;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class UserCacheIndexPublishTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        save("doc-1", "alpha", "first");
        save("doc-2", "beta", "second");
        IndexHelper.createIndex(TestGlobals.PROJECT, TestGlobals.COLL, "name");
        IndexHelper.createIndex(TestGlobals.PROJECT, TestGlobals.COLL, "label");
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void save(String id, String name, String label) {
        final var request = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("name", new JsonString(name));
        object.add("label", new JsonString(label));
        request.setObject(object);
        processor.processMessage(request);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, ?>> fieldIndexMap() throws Exception {
        return (Map<String, Map<String, ?>>) TestUtils.getPrivateField(cache.userCache(), "fieldIndexMap", Map.class);
    }

    private int cachedSlotsForCollection() throws Exception {
        final var slots = fieldIndexMap().get(Cache.getCollectionIdentifier(TestGlobals.PROJECT, TestGlobals.COLL));
        return slots == null ? 0 : slots.size();
    }

    @Test
    public void test_two_fields_admitted_concurrently_both_stay_cached() throws Exception {
        cache.evictFieldIndexAllTypes(TestGlobals.PROJECT, TestGlobals.COLL, "name");
        cache.evictFieldIndexAllTypes(TestGlobals.PROJECT, TestGlobals.COLL, "label");
        fieldIndexMap().remove(Cache.getCollectionIdentifier(TestGlobals.PROJECT, TestGlobals.COLL));
        final var ready = new CountDownLatch(2);
        final var go = new CountDownLatch(1);

        final var byName = admittingThread("name", ready, go);
        final var byLabel = admittingThread("label", ready, go);
        byName.start();
        byLabel.start();
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        byName.join(5000);
        byLabel.join(5000);

        assertEquals(2, cachedSlotsForCollection(),
                "one field's admission must not discard another field's, both taken under the same read lock");
    }

    private Thread admittingThread(String field, CountDownLatch ready, CountDownLatch go) {
        return new Thread(() -> {
            try {
                locks.lockRead(TestGlobals.PROJECT, TestGlobals.COLL);
                ready.countDown();
                if (go.await(5, TimeUnit.SECONDS)) {
                    cache.getFieldIndexAndLoadIfNecessary(TestGlobals.PROJECT, TestGlobals.COLL, field, String.class);
                }
                locks.releaseRead(TestGlobals.PROJECT, TestGlobals.COLL);
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Test
    public void test_a_reader_without_the_collection_lock_does_not_publish() throws Exception {
        cache.evictFieldIndexAllTypes(TestGlobals.PROJECT, TestGlobals.COLL, "name");
        fieldIndexMap().remove(Cache.getCollectionIdentifier(TestGlobals.PROJECT, TestGlobals.COLL));

        final var entries = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.PROJECT, TestGlobals.COLL, "name",
                String.class);

        assertNotNull(entries, "the reader is still answered from disk");
        assertEquals(0, cachedSlotsForCollection(), "only a lock holder may publish into the shared cache");
    }

    @Test
    public void test_a_probe_for_an_uncached_field_does_not_create_a_collection_map() throws Exception {
        fieldIndexMap().remove(Cache.getCollectionIdentifier(TestGlobals.PROJECT, TestGlobals.COLL));

        assertNull(
                cache.getFieldIndexAndLoadIfNecessary(TestGlobals.PROJECT, TestGlobals.COLL, "absent", String.class));

        assertNull(fieldIndexMap().get(Cache.getCollectionIdentifier(TestGlobals.PROJECT, TestGlobals.COLL)));
    }

    @Test
    public void test_a_locked_reader_still_publishes_the_field_index() throws Exception {
        cache.evictFieldIndexAllTypes(TestGlobals.PROJECT, TestGlobals.COLL, "name");
        locks.lock(TestGlobals.PROJECT, TestGlobals.COLL);
        try {
            cache.getFieldIndexAndLoadIfNecessary(TestGlobals.PROJECT, TestGlobals.COLL, "name", String.class);
        } finally {
            locks.release(TestGlobals.PROJECT, TestGlobals.COLL);
        }

        assertTrue(cachedSlotsForCollection() > 0,
                "a lock holder is the only caller allowed to publish, and must still do so");
    }

    @Test
    public void test_a_lock_free_read_still_answers_the_same_entries_as_a_locked_one() throws Exception {
        cache.evictFieldIndexAllTypes(TestGlobals.PROJECT, TestGlobals.COLL, "name");
        final var unlocked = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.PROJECT, TestGlobals.COLL, "name",
                String.class);

        locks.lock(TestGlobals.PROJECT, TestGlobals.COLL);
        try {
            assertEquals(
                    cache.getFieldIndexAndLoadIfNecessary(TestGlobals.PROJECT, TestGlobals.COLL, "name", String.class)
                            .size(),
                    unlocked.size(), "refusing to publish must not change what the lock-free reader is answered");
        } finally {
            locks.release(TestGlobals.PROJECT, TestGlobals.COLL);
        }
    }
}
