package org.ccg.mpp.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.ccg.mpp.cache.AccessKind;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.CacheSizeEstimator;
import org.ccg.mpp.cache.CacheableResource;
import org.ccg.mpp.cache.FieldIndexList;
import org.ccg.mpp.cache.FieldIndexLookup;
import org.ccg.mpp.cache.Measure;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.FieldIndexEntry;
import org.ccg.mpp.data.IdSet;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.index.FieldIndexBatch;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SharedIdListsTest {
    private static final String FIELD = "tag";
    private static final int IDS = 60;
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Map<String, String> pkIds = new HashMap<>();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        final var byValue = new HashMap<String, IdSet>();
        for (var i = 0; i < IDS; i++) {
            final var id = "document-" + i;
            byValue.computeIfAbsent("v" + i % 4, _ -> new IdSet()).add("document-" + i);
            final var object = new JsonObject();
            object.add(Globals.PK_FIELD, new JsonString(id));
            final var entry = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, object);
            TestUtils.cacheEntry(cache, TestGlobals.PROJECT, TestGlobals.COLL, entry);
            pkIds.put(entry.get_id(), entry.get_id());
        }
        final List<FieldIndexEntry<?>> entries = byValue.entrySet().stream()
                .<FieldIndexEntry<?>>map(
                        e -> new FieldIndexEntry<>(TestGlobals.PROJECT, TestGlobals.COLL, e.getKey(), e.getValue()))
                .toList();
        fs.writeIndexFile(TestGlobals.PROJECT, TestGlobals.COLL, FIELD, Map.of(String.class, entries));
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private List<FieldIndexEntry<?>> loadUnderTheCollectionLock() throws Exception {
        locks.lock(TestGlobals.PROJECT, TestGlobals.COLL);
        try {
            cache.getFieldIndexAndLoadIfNecessary(TestGlobals.PROJECT, TestGlobals.COLL, FIELD, String.class);
        } finally {
            locks.release(TestGlobals.PROJECT, TestGlobals.COLL);
        }
        return cache.residentFieldIndex(TestGlobals.PROJECT, TestGlobals.COLL,
                Cache.getIndexIdentifier(FIELD, String.class));
    }

    private long rememberedBytes(Measure measure) {
        return cache.userCache().listCacheableResources(measure).stream()
                .filter(resource -> resource.kind() == AccessKind.FIELD_INDEX
                        && Cache.getIndexIdentifier(FIELD, String.class).equals(resource.indexKey()))
                .mapToLong(CacheableResource::estimatedSizeBytes).sum();
    }

    @Test
    public void test_a_batch_adds_the_pk_string_to_a_sharing_list_and_charges_it_once() throws Exception {
        final var resident = loadUnderTheCollectionLock();
        final var shared = FieldIndexList.sharingPkIds(resident);
        assertNotNull(shared);
        assertEquals(0L, shared.sharedIdBytes(), "a list read from disk keeps its own strings");
        final var full = CacheSizeEstimator.estimateFieldIndexSize(resident);
        assertEquals(full / resident.size() * resident.size(), rememberedBytes(Measure.FULL_FORCED));

        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString("late-document"));
        final var late = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, object);
        TestUtils.cacheEntry(cache, TestGlobals.PROJECT, TestGlobals.COLL, late);
        applyBatch(late.get_id(), new JsonString("v1"));

        final var entry = resident.stream().filter(e -> "v1".equals(e.getValue())).findFirst().orElseThrow();
        assertSame(late.get_id(), ((IdSet) entry.getIds()).stored("late-document"));
        assertEquals(CacheSizeEstimator.fieldIndexIdSize("late-document"), shared.sharedIdBytes());
        assertEquals(rememberedBytes(Measure.FULL_FORCED), rememberedBytes(Measure.REMEMBERED));
        assertSame(cache.userCache().canonicalPkId(TestGlobals.PROJECT, TestGlobals.COLL, "document-7"),
                pkIds.get("document-7"));
        assertNull(cache.userCache().canonicalPkId(TestGlobals.PROJECT, TestGlobals.COLL, "absent"));
    }

    private void applyBatch(String id, JsonString value) throws Exception {
        locks.lockRead(TestGlobals.PROJECT, TestGlobals.COLL);
        locks.lockIndex(TestGlobals.PROJECT, TestGlobals.COLL, FIELD);
        try {
            final var batch = FieldIndexBatch.open(TestGlobals.PROJECT, TestGlobals.COLL, FIELD, List.of(id));
            batch.apply(id, value);
            batch.flush();
        } finally {
            locks.releaseIndex(TestGlobals.PROJECT, TestGlobals.COLL, FIELD);
            locks.releaseRead(TestGlobals.PROJECT, TestGlobals.COLL);
        }
    }

    @Test
    public void test_evicting_the_pk_list_evicts_every_list_sharing_its_strings() throws Exception {
        loadUnderTheCollectionLock();
        final var lookup = new FieldIndexLookup();
        locks.lock(TestGlobals.PROJECT, TestGlobals.COLL);
        try {
            cache.admitFieldIndexLookup(TestGlobals.PROJECT, TestGlobals.COLL, FIELD, lookup);
        } finally {
            locks.release(TestGlobals.PROJECT, TestGlobals.COLL);
        }
        assertSame(lookup, cache.fieldIndexLookup(TestGlobals.PROJECT, TestGlobals.COLL, FIELD));

        cache.userCache().evictPkIndex(TestGlobals.PROJECT, TestGlobals.COLL);

        assertNull(cache.residentFieldIndex(TestGlobals.PROJECT, TestGlobals.COLL,
                Cache.getIndexIdentifier(FIELD, String.class)));
        assertNull(cache.fieldIndexLookup(TestGlobals.PROJECT, TestGlobals.COLL, FIELD));
    }

    @Test
    public void test_a_list_admitted_without_a_resident_pk_keeps_its_own_strings_and_survives_pk_eviction()
            throws Exception {
        cache.userCache().evictPkIndex(TestGlobals.PROJECT, TestGlobals.COLL);

        final var resident = loadUnderTheCollectionLock();

        assertNull(FieldIndexList.sharingPkIds(resident));
        final var full = CacheSizeEstimator.estimateFieldIndexSize(resident);
        assertEquals(full / resident.size() * resident.size(), rememberedBytes(Measure.FULL_FORCED));
        cache.userCache().evictPkIndex(TestGlobals.PROJECT, TestGlobals.COLL);
        assertSame(resident, cache.residentFieldIndex(TestGlobals.PROJECT, TestGlobals.COLL,
                Cache.getIndexIdentifier(FIELD, String.class)));
    }
}
