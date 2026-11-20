package org.ccg.mpp.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.CacheResidents;
import org.ccg.mpp.cache.CacheSizeEstimator;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.OutsideWrites;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.Test;

public class CacheCompletenessGateTest extends CacheFixtureSupport {
    @Test
    public void test_a_dirty_read_does_not_admit_into_the_shared_cache() throws Exception {
        final var cache = new Cache();
        final var fs = IocContainer.get(FileSystem.class);
        TestUtils.createTestProjectAndCollection();
        for (int i = 1; i <= 2; i++) {
            final var o = new JsonObject();
            o.addProperty(Globals.PK_FIELD, "d" + i);
            final var e = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, o);
            e.setPage(0L);
            fs.insertIntoCollection(e);
        }

        final var loaded = cache.getWholeCollection(TestGlobals.PROJECT, TestGlobals.COLL);

        assertEquals(2, loaded.size(), "the reader still gets a complete answer");
        assertNull(cache.userCache().getCachedCollection(TestGlobals.PROJECT, TestGlobals.COLL),
                "a reader holding no collection lock must not publish its snapshot as the shared cache");
    }

    @Test
    public void test_a_locked_read_does_admit_into_the_shared_cache() throws Exception {
        final var cache = new Cache();
        final var fs = IocContainer.get(FileSystem.class);
        final var locks = IocContainer.get(org.ccg.mpp.concurrency.ResourceLocking.class);
        TestUtils.createTestProjectAndCollection();
        final var o = new JsonObject();
        o.addProperty(Globals.PK_FIELD, "d1");
        final var e = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, o);
        e.setPage(0L);
        fs.insertIntoCollection(e);

        locks.lockRead(TestGlobals.PROJECT, TestGlobals.COLL);
        try {
            cache.getWholeCollection(TestGlobals.PROJECT, TestGlobals.COLL);
        } finally {
            locks.releaseRead(TestGlobals.PROJECT, TestGlobals.COLL);
        }

        assertNotNull(cache.userCache().getCachedCollection(TestGlobals.PROJECT, TestGlobals.COLL),
                "a properly locked scan still populates the shared cache");
    }

    @Test
    public void test_get_whole_collection_reloads_when_cache_incomplete_vs_pk_index() throws Exception {
        final var cache = new Cache();
        final var fs = IocContainer.get(FileSystem.class);
        TestUtils.createTestProjectAndCollection();

        for (int i = 1; i <= 2; i++) {
            final var o = new JsonObject();
            o.addProperty(Globals.PK_FIELD, "id" + i);
            final var e = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, o);
            e.setPage(0L);
            fs.insertIntoCollection(e);
        }

        final var collId = Cache.getCollectionIdentifier(TestGlobals.PROJECT, TestGlobals.COLL);
        final var cachedObj = new JsonObject();
        cachedObj.addProperty(Globals.PK_FIELD, "id1");
        injectCachedEntry(collId, DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, cachedObj));
        // A lagging page count of 1 would previously have wrongly accepted the 1-entry cache as complete.
        final var pageEntry = new org.ccg.mpp.data.admin.AdminPageEntry(TestGlobals.PROJECT, TestGlobals.COLL, 0);
        pageEntry.setEntryCount(1);
        injectPages(collId, new ArrayList<>(List.of(pageEntry)));

        final var result = cache.getWholeCollection(TestGlobals.PROJECT, TestGlobals.COLL);

        assertEquals(2, result.size(), "PK index size (2) must force a reload of the incomplete cache");
        assertTrue(result.containsKey("id1"));
        assertTrue(result.containsKey("id2"));
    }

    @Test
    public void test_streamCollection_skips_cache_when_disabled() throws Exception {
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", -1L);
        try {
            Cache cache = IocContainer.get(Cache.class);
            TestUtils.createTestProjectAndCollection();
            final var stream = cache.streamCollection(TestGlobals.PROJECT, TestGlobals.COLL);
            assertNotNull(stream);
            stream.close();
            final var userCache = IocContainer.get(org.ccg.mpp.cache.UserCache.class);
            final var collectionMap = CacheResidents.documentsByCollection(userCache);
            assertFalse(
                    collectionMap.containsKey(Cache.getCollectionIdentifier(TestGlobals.PROJECT, TestGlobals.COLL)));
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", original);
        }
    }

    @Test
    public void test_streamCollection_fully_cached_streams_from_cache() throws Exception {
        Cache cache = new Cache();
        FileSystem fsMock = mock(FileSystem.class);
        TestUtils.setPrivateField(cache, "fs", fsMock);

        final var collId = Cache.getCollectionIdentifier("userProject", "c1");
        final var obj = new JsonObject();
        obj.addProperty(Globals.PK_FIELD, "id1");
        injectCachedEntry(collId, DbEntry.fromJsonObject("userProject", "c1", obj));
        injectPkIndex(collId,
                new ArrayList<>(List.of(new org.ccg.mpp.data.PkIndexEntry("userProject", "c1", "id1", 0, 1, 0))));
        final var pageEntry = new org.ccg.mpp.data.admin.AdminPageEntry("userProject", "c1", 0);
        pageEntry.setEntryCount(1);
        injectPages(collId, new ArrayList<>(List.of(pageEntry)));

        final List<DbEntry> result;
        try (var stream = cache.streamCollection("userProject", "c1")) {
            result = stream.toList();
        }

        assertEquals(1, result.size());
        assertEquals("id1", result.getFirst().get_id());
        verify(fsMock, never()).readWholeCollectionPage(anyString(), anyString(), anyLong());
        verify(fsMock, never()).streamEntries(anyString(), anyString());
    }

    @Test
    public void test_streamCollection_not_cached_asks_headroom_for_the_page_heap_after_reading_it() throws Exception {
        Cache cache = new Cache();
        FileSystem fsMock = mock(FileSystem.class);
        TestUtils.setPrivateField(cache, "fs", fsMock);
        org.ccg.mpp.cache.MemoryManagement mmMock = mock(org.ccg.mpp.cache.MemoryManagement.class);
        TestUtils.setPrivateField(cache, "memoryManagement", mmMock);

        final var collId = Cache.getCollectionIdentifier("userProject", "c1");
        final var pageEntry = new org.ccg.mpp.data.admin.AdminPageEntry("userProject", "c1", 0);
        pageEntry.setEntryCount(2);
        pageEntry.setPageSize(1234L);
        injectPages(collId, new ArrayList<>(List.of(pageEntry)));

        final var page = new HashMap<String, DbEntry>();
        final var o1 = new JsonObject();
        o1.addProperty(Globals.PK_FIELD, "id1");
        final var o2 = new JsonObject();
        o2.addProperty(Globals.PK_FIELD, "id2");
        page.put("id1", DbEntry.fromJsonObject("userProject", "c1", o1));
        page.put("id2", DbEntry.fromJsonObject("userProject", "c1", o2));
        when(fsMock.readWholeCollectionPage("userProject", "c1", 0L)).thenReturn(page);

        final List<DbEntry> result;
        try (var stream = cache.streamCollection("userProject", "c1")) {
            result = stream.toList();
        }

        assertEquals(2, result.size());
        final var pageHeap = CacheSizeEstimator.estimatePageHeap(page.values());
        assertNotEquals(1234L, pageHeap, "the fixture must tell the on-disk size and the page heap apart");
        final var order = inOrder(fsMock, mmMock);
        order.verify(fsMock).readWholeCollectionPage("userProject", "c1", 0L);
        order.verify(mmMock).ensureHeadroomForBytes(pageHeap);
        verify(mmMock, never()).ensureHeadroomForBytes(1234L);
    }

    @Test
    public void test_streamCollection_no_page_metadata_falls_back_to_stream_entries() throws Exception {
        Cache cache = new Cache();
        FileSystem fsMock = mock(FileSystem.class);
        TestUtils.setPrivateField(cache, "fs", fsMock);

        final var o1 = new JsonObject();
        o1.addProperty(Globals.PK_FIELD, "id1");
        when(fsMock.streamEntries("userProject", "c1"))
                .thenReturn(Stream.of(DbEntry.fromJsonObject("userProject", "c1", o1)));

        final List<DbEntry> result;
        try (var stream = cache.streamCollection("userProject", "c1")) {
            result = stream.toList();
        }

        assertEquals(1, result.size());
        verify(fsMock).streamEntries("userProject", "c1");
    }

    @Test
    public void test_streamCollection_caching_disabled_uses_disk_path() throws Exception {
        final var config = Configuration.getInstance();
        final long original = config.getMaxMemoryBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", -1L);
        try {
            Cache cache = new Cache();
            FileSystem fsMock = mock(FileSystem.class);
            TestUtils.setPrivateField(cache, "fs", fsMock);
            org.ccg.mpp.cache.MemoryManagement mmMock = mock(org.ccg.mpp.cache.MemoryManagement.class);
            TestUtils.setPrivateField(cache, "memoryManagement", mmMock);

            final var collId = Cache.getCollectionIdentifier("userProject", "c1");
            final var cachedObj = new JsonObject();
            cachedObj.addProperty(Globals.PK_FIELD, "stale");
            injectCachedEntry(collId, DbEntry.fromJsonObject("userProject", "c1", cachedObj));
            final var pageEntry = new org.ccg.mpp.data.admin.AdminPageEntry("userProject", "c1", 0);
            pageEntry.setEntryCount(1);
            injectPages(collId, new ArrayList<>(List.of(pageEntry)));
            final var page = new HashMap<String, DbEntry>();
            final var o1 = new JsonObject();
            o1.addProperty(Globals.PK_FIELD, "fromDisk");
            page.put("fromDisk", DbEntry.fromJsonObject("userProject", "c1", o1));
            when(fsMock.readWholeCollectionPage("userProject", "c1", 0L)).thenReturn(page);

            final List<DbEntry> result;
            try (var stream = cache.streamCollection("userProject", "c1")) {
                result = stream.toList();
            }

            assertEquals(1, result.size());
            assertEquals("fromDisk", result.getFirst().get_id());
            verify(fsMock).readWholeCollectionPage("userProject", "c1", 0L);
        } finally {
            TestUtils.setPrivateField(config, "maxMemoryBytes", original);
        }
    }

    @Test
    public void test_the_completeness_gate_rejects_a_superset() throws Exception {
        final var cache = new Cache();
        final var projectName = "supersetProject";
        final var collName = "supersetColl";
        final var live = new DbEntry();
        live.set_id("live");
        final var deleted = new DbEntry();
        deleted.set_id("deleted");
        cache.addEntryToCache(projectName, collName, live);
        cache.addEntryToCache(projectName, collName, deleted);
        cache.getPkIndexAndLoadIfNecessary(projectName, collName)
                .add(new org.ccg.mpp.data.PkIndexEntry(projectName, collName, "live", 0, 1, 0));

        final var result = cache.getWholeCollection(projectName, collName);

        assertFalse(result.containsKey("deleted"),
                "a cached map holding more documents than the PK index is not complete, it is poisoned: serving"
                        + " it returns a deleted document from every later fully locked scan");
    }

    @Test
    public void test_stream_rejects_a_cached_map_larger_than_the_pk_index() throws Exception {
        final var cache = new Cache();
        final var fsMock = mock(FileSystem.class);
        TestUtils.setPrivateField(cache, "fs", fsMock);
        when(fsMock.streamEntries(anyString(), anyString())).thenReturn(java.util.stream.Stream.of());

        final var collId = Cache.getCollectionIdentifier("supersetProject", "c1");
        final var live = new JsonObject();
        live.addProperty(Globals.PK_FIELD, "live");
        final var deleted = new JsonObject();
        deleted.addProperty(Globals.PK_FIELD, "deleted");
        injectCachedEntry(collId, DbEntry.fromJsonObject("supersetProject", "c1", live));
        injectCachedEntry(collId, DbEntry.fromJsonObject("supersetProject", "c1", deleted));
        injectPkIndex(collId,
                new ArrayList<>(List.of(new org.ccg.mpp.data.PkIndexEntry("supersetProject", "c1", "live", 0, 1, 0))));

        final List<DbEntry> result;
        try (var stream = cache.streamCollection("supersetProject", "c1")) {
            result = stream.toList();
        }

        assertTrue(result.stream().noneMatch(e -> "deleted".equals(e.get_id())),
                "a cached map holding more documents than the PK index is not complete, it is poisoned: serving it"
                        + " returns a deleted document from every later fully locked scan");
    }

    @Test
    public void test_an_unknown_lock_free_count_declines_the_cached_map() throws Exception {
        final var cache = new Cache();
        final var fs = IocContainer.get(FileSystem.class);
        TestUtils.createTestProjectAndCollection();
        final var collId = Cache.getCollectionIdentifier(TestGlobals.PROJECT, TestGlobals.COLL);
        for (int i = 1; i <= 2; i++) {
            final var onDisk = new JsonObject();
            onDisk.addProperty(Globals.PK_FIELD, "d" + i);
            fs.insertIntoCollection(DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, onDisk));
            final var stale = new JsonObject();
            stale.addProperty(Globals.PK_FIELD, "stale" + i);
            injectCachedEntry(collId, DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, stale));
        }
        assertEquals(2, cache.pkCountForCoverage(TestGlobals.PROJECT, TestGlobals.COLL));
        final var pkIndex = java.nio.file.Path.of(TestGlobals.PATH, TestGlobals.PROJECT, TestGlobals.COLL,
                TestGlobals.COLL + "-pk.idx");
        java.nio.file.Files.setLastModifiedTime(pkIndex, java.nio.file.attribute.FileTime.fromMillis(0L));
        OutsideWrites.madeTo(pkIndex.toFile());

        assertEquals(-1, cache.pkCountForCoverage(TestGlobals.PROJECT, TestGlobals.COLL));
        final var loaded = cache.getWholeCollection(TestGlobals.PROJECT, TestGlobals.COLL);
        assertTrue(loaded.containsKey("d1") && loaded.containsKey("d2"), "an unknown count reads the pages");
        assertFalse(loaded.containsKey("stale1"));
        try (var streamed = cache.streamCollection(TestGlobals.PROJECT, TestGlobals.COLL)) {
            assertEquals(List.of("d1", "d2"), streamed.map(DbEntry::get_id).sorted().toList());
        }
    }
}
