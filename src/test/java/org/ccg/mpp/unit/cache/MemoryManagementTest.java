package org.ccg.mpp.unit.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import org.ccg.mpp.cache.AccessKind;
import org.ccg.mpp.cache.AdmissionDecision;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.CacheResidents;
import org.ccg.mpp.cache.CacheableResource;
import org.ccg.mpp.cache.MemoryManagement;
import org.ccg.mpp.cache.UsageCounter;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.FieldIndexEntry;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MemoryManagementTest {

    private volatile long savedMaxMemoryBytes;

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
        savedMaxMemoryBytes = Configuration.getInstance().getMaxMemoryBytes();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        final var config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "maxMemoryBytes", savedMaxMemoryBytes);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.stopSweepThread();
        TestUtils.resetUsageCounters(TestUtils.usageTrackerOf(mm));
        TestUtils.standardTearDown();
    }

    private void setMaxMemory(long bytes) throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setPrivateField(Configuration.getInstance(), "maxMemoryBytes", bytes);
    }

    @Test
    public void test_recordAccess_increments_counter() {
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, "myProject", "myColl", null);
        mm.recordAccess(AccessKind.COLLECTION, "myProject", "myColl", null);
        final var counter = mm.getCounter(AccessKind.COLLECTION, "myProject", "myColl", null);
        assertNotNull(counter);
        assertEquals(2L, counter.getAccessCount());
        assertTrue(counter.getLastAccessMillis() > 0L);
    }

    @Test
    public void test_recordAccess_ignores_admin_project() {
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, Globals.ADMIN_PROJECT_NAME, "projects", null);
        assertNull(mm.getCounter(AccessKind.COLLECTION, Globals.ADMIN_PROJECT_NAME, "projects", null));
    }

    @Test
    public void test_recordAccess_skipped_when_caching_disabled() throws NoSuchFieldException, IllegalAccessException {
        setMaxMemory(-1L);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, "myProject", "myColl", null);
        assertNull(mm.getCounter(AccessKind.COLLECTION, "myProject", "myColl", null));
    }

    @Test
    public void test_isCachingDisabled_when_config_minus_one() throws NoSuchFieldException, IllegalAccessException {
        setMaxMemory(-1L);
        final var mm = IocContainer.get(MemoryManagement.class);
        assertTrue(mm.isCachingDisabled());
        assertFalse(mm.isCacheUnlimited());
    }

    @Test
    public void test_isCacheUnlimited_when_config_zero() throws NoSuchFieldException, IllegalAccessException {
        setMaxMemory(0L);
        final var mm = IocContainer.get(MemoryManagement.class);
        assertTrue(mm.isCacheUnlimited());
        assertFalse(mm.isCachingDisabled());
    }

    @Test
    public void test_runEvictionSweep_noop_when_unlimited() throws Exception {
        setMaxMemory(0L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache("userColl", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.runEvictionSweep();
        assertEquals(1, cache.listCacheableResources().size());
    }

    @Test
    public void test_runEvictionSweep_noop_when_disabled() throws Exception {
        setMaxMemory(-1L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache("userColl", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.runEvictionSweep();
        assertEquals(1, cache.listCacheableResources().size());
    }

    @Test
    public void test_runEvictionSweep_noop_when_cache_under_cap() throws Exception {
        setMaxMemory(10L * 1024L * 1024L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache("userColl", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.runEvictionSweep();
        assertEquals(1, cache.listCacheableResources().size());
    }

    @Test
    public void test_runEvictionSweep_evicts_lowest_LFU_first() throws Exception {
        setMaxMemory(1L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache("hot", 10);
        seedCollectionCache("cold", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        for (int i = 0; i < 5; i++) {
            mm.recordAccess(AccessKind.COLLECTION, "userProject", "hot", null);
        }
        mm.recordAccess(AccessKind.COLLECTION, "userProject", "cold", null);
        mm.runEvictionSweep();
        final var remaining = cache.listCacheableResources();
        assertTrue(remaining.stream().noneMatch(r -> r.collName().equals("cold")), "cold should be evicted first");
    }

    @Test
    public void test_runEvictionSweep_skips_resource_held_by_reader() throws Exception {
        setMaxMemory(1L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache("lockedColl", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, "userProject", "lockedColl", null);
        final var locks = IocContainer.get(org.ccg.mpp.concurrency.ResourceLocking.class);
        locks.lockRead("userProject", "lockedColl");
        try {
            mm.runEvictionSweep();
            final var remaining = cache.listCacheableResources();
            assertTrue(remaining.stream().anyMatch(r -> r.collName().equals("lockedColl")),
                    "a resource held by a reader must not be evicted");
        } finally {
            locks.releaseRead("userProject", "lockedColl");
        }
    }

    @Test
    public void test_runEvictionSweep_respects_priority_tier() throws Exception {
        // PK index sizes by ESTIMATED_PK_ENTRY_BYTES (small); collection by entry bytes.
        // Set cap so PK index alone fits, but collection + PK index together do not.
        final var cache = IocContainer.get(Cache.class);
        seedPkIndex("coll");
        seedCollectionCache("coll", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, "userProject", "coll", null);
        mm.recordAccess(AccessKind.PK_INDEX, "userProject", "coll", null);
        final var pkBytes = cache.listCacheableResources().stream().filter(r -> r.kind() == AccessKind.PK_INDEX)
                .mapToLong(CacheableResource::estimatedSizeBytes).sum();
        setMaxMemory(pkBytes);
        mm.runEvictionSweep();
        final var remaining = cache.listCacheableResources();
        assertTrue(remaining.stream().anyMatch(r -> r.kind() == AccessKind.PK_INDEX),
                "PK_INDEX should survive eviction");
        assertTrue(remaining.stream().noneMatch(r -> r.kind() == AccessKind.COLLECTION),
                "COLLECTION should be evicted first");
    }

    @Test
    public void test_putCounter_and_clearCounter() {
        final var mm = IocContainer.get(MemoryManagement.class);
        final var counter = new UsageCounter(AccessKind.COLLECTION, "db", "coll", "", 5L, 100L);
        mm.putCounter(counter);
        assertNotNull(mm.getCounter(AccessKind.COLLECTION, "db", "coll", null));
        mm.clearCounter(AccessKind.COLLECTION, "db", "coll", null);
        assertNull(mm.getCounter(AccessKind.COLLECTION, "db", "coll", null));
    }

    @Test
    public void test_getCountersSnapshot_isolated_from_internal() {
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, "db", "coll", null);
        final var snapshot = mm.getCountersSnapshot();
        assertEquals(1, snapshot.size());
    }

    @Test
    public void test_buildKey_includes_all_components() {
        final var key = MemoryManagement.buildKey(AccessKind.FIELD_INDEX, "db", "coll", "field");
        assertTrue(key.contains("FIELD_INDEX"));
        assertTrue(key.contains("db"));
        assertTrue(key.contains("coll"));
        assertTrue(key.contains("field"));
    }

    @Test
    public void test_buildKey_handles_null_indexKey() {
        final var key = MemoryManagement.buildKey(AccessKind.COLLECTION, "db", "coll", null);
        assertTrue(key.contains("COLLECTION"));
        assertTrue(key.contains("db"));
        assertTrue(key.contains("coll"));
    }

    @Test
    public void test_startSweepThread_noop_when_disabled() throws NoSuchFieldException, IllegalAccessException {
        setMaxMemory(-1L);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.startSweepThread();
        final var scheduler = TestUtils.getPrivateField(mm, "scheduler",
                java.util.concurrent.ScheduledExecutorService.class);
        assertNull(scheduler);
    }

    @Test
    public void test_stopSweepThread_safe_when_not_started() {
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.stopSweepThread();
    }

    @Test
    public void test_startSweepThread_starts_scheduler_when_enabled() throws Exception {
        setMaxMemory(1024L);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.startSweepThread();
        try {
            final var scheduler = TestUtils.getPrivateField(mm, "scheduler",
                    java.util.concurrent.ScheduledExecutorService.class);
            assertNotNull(scheduler);
        } finally {
            mm.stopSweepThread();
        }
    }

    @Test
    public void test_loadProfileFromAdmin_no_throw_when_empty() {
        final var mm = IocContainer.get(MemoryManagement.class);
        assertDoesNotThrow(mm::loadProfileFromAdmin);
    }

    @Test
    public void test_loadProfileFromAdmin_skipped_when_caching_disabled() throws Exception {
        setMaxMemory(-1L);
        final var mm = IocContainer.get(MemoryManagement.class);
        assertDoesNotThrow(mm::loadProfileFromAdmin);
    }

    @Test
    public void test_loadProfileFromAdmin_reads_existing_records() throws Exception {
        org.ccg.mpp.ops.AdminOperationHelper
                .saveProjectEntry(new org.ccg.mpp.data.admin.AdminProjectEntry("userProject"));
        org.ccg.mpp.ops.AdminOperationHelper
                .saveCollectionEntry(new org.ccg.mpp.data.admin.AdminCollEntry("userProject", "userColl"));
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, "userProject", "userColl", null);
        org.ccg.mpp.ops.AdminOperationHelper
                .flushCollectionUsage(java.util.List.of(new org.ccg.mpp.cache.UsageCounter(AccessKind.COLLECTION,
                        "userProject", "userColl", null, 1L, System.currentTimeMillis())));
        TestUtils.resetUsageCounters(TestUtils.usageTrackerOf(mm));
        mm.loadProfileFromAdmin();
        assertNotNull(mm.getCounter(AccessKind.COLLECTION, "userProject", "userColl", null));
    }

    @Test
    public void test_runEvictionSweep_handles_field_index_tier() throws Exception {
        setMaxMemory(1L);
        final var cache = IocContainer.get(Cache.class);
        seedFieldIndex("coll");
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.FIELD_INDEX, "userProject", "coll", "f|String");
        mm.runEvictionSweep();
        final var remaining = cache.listCacheableResources();
        assertTrue(remaining.stream().noneMatch(r -> r.kind() == AccessKind.FIELD_INDEX),
                "FIELD_INDEX should be evicted under tight cap");
    }

    @Test
    public void test_runEvictionSweep_skips_concurrent_call() throws Exception {
        setMaxMemory(1L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache("coll", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        final var sweepRunning = TestUtils.getPrivateField(mm, "sweepRunning",
                java.util.concurrent.atomic.AtomicBoolean.class);
        sweepRunning.set(true);
        try {
            mm.runEvictionSweep();
            assertEquals(1, cache.listCacheableResources().size());
        } finally {
            sweepRunning.set(false);
        }
    }

    @Test
    public void test_runEvictionSweepSafely_swallows_exception() throws Exception {
        final var mm = IocContainer.get(MemoryManagement.class);
        final var method = MemoryManagement.class.getDeclaredMethod("runEvictionSweepSafely");
        method.setAccessible(true);
        method.invoke(mm);
    }

    @Test
    public void test_submitCleanupTask_invokes_background_manager() throws Exception {
        final var mm = IocContainer.get(MemoryManagement.class);
        final var method = MemoryManagement.class.getDeclaredMethod("submitCleanupTask");
        method.setAccessible(true);
        method.invoke(mm);
    }

    @Test
    public void test_tier_ordinal_covers_all_kinds() throws Exception {
        setMaxMemory(1L);
        seedCollectionCache("coll1", 5);
        seedPkIndex("coll2");
        seedFieldIndex("coll3");
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, "userProject", "coll1", null);
        mm.recordAccess(AccessKind.PK_INDEX, "userProject", "coll2", null);
        mm.recordAccess(AccessKind.FIELD_INDEX, "userProject", "coll3", "f|String");
        mm.runEvictionSweep();
    }

    @Test
    public void test_admission_check_admits_when_cache_has_room() throws Exception {
        setMaxMemory(1024L * 1024L);
        final var mm = IocContainer.get(MemoryManagement.class);
        assertEquals(AdmissionDecision.ADMIT, mm.admissionCheck(100L));
    }

    @Test
    public void test_admission_check_admits_when_unlimited() throws Exception {
        setMaxMemory(0L);
        final var mm = IocContainer.get(MemoryManagement.class);
        assertEquals(AdmissionDecision.ADMIT, mm.admissionCheck(1_000_000_000L));
    }

    @Test
    public void test_admission_check_rejects_when_caching_disabled() throws Exception {
        setMaxMemory(-1L);
        final var mm = IocContainer.get(MemoryManagement.class);
        assertEquals(AdmissionDecision.REJECT, mm.admissionCheck(1L));
    }

    @Test
    public void test_admission_check_rejects_when_admission_would_exceed_cap() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache("coll", 10);
        final var existing = cache.listCacheableResources().stream().mapToLong(CacheableResource::estimatedSizeBytes)
                .sum();
        setMaxMemory(existing + 100L);
        final var mm = IocContainer.get(MemoryManagement.class);
        assertEquals(AdmissionDecision.ADMIT, mm.admissionCheck(50L));
        assertEquals(AdmissionDecision.REJECT, mm.admissionCheck(200L));
    }

    @Test
    public void test_userCacheBytes_reflects_resources() {
        final var mm = IocContainer.get(MemoryManagement.class);
        assertEquals(0L, mm.userCacheBytes());
        seedCollectionCache("coll", 10);
        assertTrue(mm.userCacheBytes() > 0L);
    }

    @Test
    public void test_usageRetentionMillis_is_24h() {
        final var mm = IocContainer.get(MemoryManagement.class);
        assertEquals(24L * 60L * 60L * 1000L, mm.usageRetentionMillis());
    }

    @Test
    public void test_ensureHeadroomForBytes_noop_under_budget() throws Exception {
        setMaxMemory(10L * 1024L * 1024L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache("userColl", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.ensureHeadroomForBytes(100L);
        assertEquals(1, cache.listCacheableResources().size());
    }

    @Test
    public void test_ensureHeadroomForBytes_noop_when_disabled() throws Exception {
        setMaxMemory(-1L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache("userColl", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.ensureHeadroomForBytes(1_000_000L);
        assertEquals(1, cache.listCacheableResources().size());
    }

    @Test
    public void test_ensureHeadroomForBytes_noop_when_unlimited() throws Exception {
        setMaxMemory(0L);
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache("userColl", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.ensureHeadroomForBytes(1_000_000L);
        assertEquals(1, cache.listCacheableResources().size());
    }

    @Test
    public void test_ensureHeadroomForBytes_evicts_lfu_to_make_room() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        seedCollectionCache("hot", 10);
        seedCollectionCache("cold", 10);
        final var mm = IocContainer.get(MemoryManagement.class);
        for (int i = 0; i < 5; i++) {
            mm.recordAccess(AccessKind.COLLECTION, "userProject", "hot", null);
        }
        mm.recordAccess(AccessKind.COLLECTION, "userProject", "cold", null);
        setMaxMemory(1L);
        mm.ensureHeadroomForBytes(1L);
        final var remaining = cache.listCacheableResources();
        assertTrue(remaining.stream().noneMatch(r -> r.collName().equals("cold")),
                "cold should be evicted first to make headroom");
    }

    private void seedFieldIndex(String collName) {
        CacheResidents.residentFieldIndex(IocContainer.get(org.ccg.mpp.cache.UserCache.class),
                Cache.getCollectionIdentifier("userProject", collName), "f|String",
                List.of(new FieldIndexEntry<>("userProject", collName, "value", new HashSet<>(List.of("id1")))));
    }

    private void seedCollectionCache(String collName, int count) {
        final var userCache = IocContainer.get(org.ccg.mpp.cache.UserCache.class);
        for (int i = 0; i < count; i++) {
            final var obj = new JsonObject();
            obj.addProperty(Globals.PK_FIELD, "id" + i);
            obj.addProperty("v", "x".repeat(64));
            CacheResidents.residentDocument(userCache, Cache.getCollectionIdentifier("userProject", collName),
                    DbEntry.fromJsonObject("userProject", collName, obj));
        }
    }

    private void seedPkIndex(String collName) {
        CacheResidents.residentPk(IocContainer.get(org.ccg.mpp.cache.UserCache.class),
                Cache.getCollectionIdentifier("userProject", collName),
                List.of(new PkIndexEntry("userProject", collName, "id0", 0, 10, 0)));
    }
}
