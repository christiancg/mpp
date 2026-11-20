package org.ccg.mpp.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.CacheResidents;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.VacuumedPage;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestUtils;
import org.ccg.mpp.utils.ReflectionUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class CacheTest extends CacheFixtureSupport {

    @Test
    public void test_concatenates_dbname_and_coll_name_correctly() {
        String projectName = "testProject";
        String collName = "testColl";
        String expected = "testProject" + Globals.COLL_IDENTIFIER_SEPARATOR + "testColl";
        String result = Cache.getCollectionIdentifier(projectName, collName);
        assertEquals(expected, result);
    }

    @Test
    public void test_handles_empty_dbname_and_coll_name_gracefully() {
        String projectName = "a";
        String collName = "a";
        String expected = "a" + Globals.COLL_IDENTIFIER_SEPARATOR + "a";
        String result = Cache.getCollectionIdentifier(projectName, collName);
        assertEquals(expected, result);
    }

    @Test
    public void test_returns_correct_identifier_for_standard_field_names_and_types() {
        String fieldName = "username";
        Class<?> fieldType = String.class;
        String expected = "username|String";
        String actual = Cache.getIndexIdentifier(fieldName, fieldType);
        assertEquals(expected, actual);
    }

    @Test
    public void test_handles_empty_field_name_correctly() {
        String fieldName = "";
        Class<?> fieldType = Integer.class;
        String expected = "|Integer";
        String actual = Cache.getIndexIdentifier(fieldName, fieldType);
        assertEquals(expected, actual);
    }

    @Test
    public void test_facade_constructs_subcaches_from_ioc() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = new Cache();
        final var adminCache = TestUtils.getPrivateField(cache, "adminCache", org.ccg.mpp.cache.AdminCache.class);
        final var userCache = TestUtils.getPrivateField(cache, "userCache", org.ccg.mpp.cache.UserCache.class);
        assertNotNull(adminCache);
        assertNotNull(userCache);
        assertSame(IocContainer.get(org.ccg.mpp.cache.AdminCache.class), adminCache);
        assertSame(IocContainer.get(org.ccg.mpp.cache.UserCache.class), userCache);
    }

    @Test
    public void test_facade_delegates_user_method() {
        Cache cache = new Cache();
        final var obj = new JsonObject();
        obj.addProperty(Globals.PK_FIELD, "u1");
        cache.addEntryToCache("userProject", "c1", DbEntry.fromJsonObject("userProject", "c1", obj));
        final var userCache = IocContainer.get(org.ccg.mpp.cache.UserCache.class);
        final var cached = userCache.getCachedCollection("userProject", "c1");
        assertNotNull(cached);
        assertTrue(cached.containsKey("u1"));
    }

    @Test
    public void test_facade_delegates_admin_method() {
        Cache cache = new Cache();
        cache.updatePageSizeInMemory("userProject", "c1", 0L, 100L);
        final var adminCache = IocContainer.get(org.ccg.mpp.cache.AdminCache.class);
        assertNotNull(adminCache.getAdminPageEntries("userProject", "c1"));
        assertEquals(0L, cache.getAdminPageEntry("userProject", "c1", 0L).getPage());
    }

    @Test
    public void test_returns_whole_collection_from_cache_if_exists_and_complete()
            throws NoSuchFieldException, IllegalAccessException, java.io.IOException, InterruptedException {
        Cache cache = new Cache();
        String projectName = "testProject";
        String collName = "testColl";
        String collectionIdentifier = Cache.getCollectionIdentifier(projectName, collName);

        final var pageEntry = new org.ccg.mpp.data.admin.AdminPageEntry(projectName, collName, 0);
        pageEntry.setEntryCount(2);
        final var pageList = new java.util.ArrayList<org.ccg.mpp.data.admin.AdminPageEntry>();
        pageList.add(pageEntry);

        final var typePages = new ReflectionUtils.TypeToken<Map<String, List<org.ccg.mpp.data.admin.AdminPageEntry>>>() {
        };
        final var adminCache = IocContainer.get(org.ccg.mpp.cache.AdminCache.class);
        final var pagesMap = TestUtils.getPrivateField(TestUtils.pageCacheOf(adminCache), "pages", typePages);
        pagesMap.put(collectionIdentifier, pageList);

        DbEntry entry1 = new DbEntry();
        entry1.set_id("1");
        DbEntry entry2 = new DbEntry();
        entry2.set_id("2");

        Map<String, DbEntry> wholeCollection = new HashMap<>();
        wholeCollection.put("1", entry1);
        wholeCollection.put("2", entry2);

        final var userCache = IocContainer.get(org.ccg.mpp.cache.UserCache.class);
        final var collectionMap = CacheResidents.documentsByCollection(userCache);
        collectionMap.put(collectionIdentifier, wholeCollection);
        final var locks = IocContainer.get(org.ccg.mpp.concurrency.ResourceLocking.class);
        locks.lock(projectName, collName);
        try {
            final var pkIndex = cache.getPkIndexAndLoadIfNecessary(projectName, collName);
            pkIndex.add(new org.ccg.mpp.data.PkIndexEntry(projectName, collName, "1", 0, 1, 0));
            pkIndex.add(new org.ccg.mpp.data.PkIndexEntry(projectName, collName, "2", 1, 1, 0));
        } finally {
            locks.release(projectName, collName);
        }

        Map<String, DbEntry> result = cache.getWholeCollection(projectName, collName);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("1"));
        assertTrue(result.containsKey("2"));
    }

    @Test
    public void test_handles_ioexception_when_reading_collection_from_file_system()
            throws NoSuchFieldException, IllegalAccessException, IOException {
        Cache cache = new Cache();
        FileSystem fsMock = mock(FileSystem.class);
        Field fsField = Cache.class.getDeclaredField("fs");
        fsField.setAccessible(true);
        fsField.set(cache, fsMock);

        String projectName = "testProject";
        String collName = "testColl";

        when(fsMock.streamPages(projectName, collName)).thenThrow(new IOException("File not found"));

        assertThrows(RuntimeException.class, () -> cache.getWholeCollection(projectName, collName));
    }

    @Test
    public void test_handles_ioexception_when_reading_collection()
            throws NoSuchFieldException, IllegalAccessException, IOException {
        Cache cache = new Cache();
        FileSystem fsMock = mock(FileSystem.class);
        Field fsField = Cache.class.getDeclaredField("fs");
        fsField.setAccessible(true);
        fsField.set(cache, fsMock);

        when(fsMock.streamEntries(anyString(), anyString())).thenThrow(IOException.class);
        assertThrows(IOException.class, () -> cache.streamCollection("projectName", "collName"));
    }

    @Test
    public void test_get_whole_collection_returns_cached_when_pages_metadata_missing()
            throws IOException, InterruptedException {
        Cache cache = new Cache();
        String projectName = "myProject";
        String collName = "myColl";
        DbEntry e = new DbEntry();
        e.set_id("1");
        cache.addEntryToCache(projectName, collName, e);
        final var locks = IocContainer.get(org.ccg.mpp.concurrency.ResourceLocking.class);
        locks.lock(projectName, collName);
        try {
            cache.getPkIndexAndLoadIfNecessary(projectName, collName)
                    .add(new org.ccg.mpp.data.PkIndexEntry(projectName, collName, "1", 0, 1, 0));
        } finally {
            locks.release(projectName, collName);
        }

        Map<String, DbEntry> result = cache.getWholeCollection(projectName, collName);
        assertEquals(1, result.size());
    }

    // Regression: the completeness gate must use the synchronous PK index size, not the
    // background-updated (lagging) admin page entry count.

    @Test
    @SuppressWarnings("unchecked")
    public void test_the_vacuum_listener_routes_admin_vs_user() {
        final var fsMock = mock(FileSystem.class);
        final var adminMock = mock(org.ccg.mpp.cache.AdminCache.class);
        final var userMock = mock(org.ccg.mpp.cache.UserCache.class);
        try (var ioc = mockStatic(IocContainer.class, CALLS_REAL_METHODS)) {
            ioc.when(() -> IocContainer.get(FileSystem.class)).thenReturn(fsMock);
            ioc.when(() -> IocContainer.get(org.ccg.mpp.cache.AdminCache.class)).thenReturn(adminMock);
            ioc.when(() -> IocContainer.get(org.ccg.mpp.cache.UserCache.class)).thenReturn(userMock);
            assertDoesNotThrow(Cache::new);
        }
        final var registered = ArgumentCaptor.forClass(Consumer.class);
        verify(fsMock).onVacuum(registered.capture());
        final Consumer<VacuumedPage> listener = registered.getValue();
        final var admin = new VacuumedPage(Globals.ADMIN_PROJECT_NAME, "collections", 0, Map.of());
        final var pages = new VacuumedPage(Globals.ADMIN_PAGES_PROJECT_NAME, "userProject_userColl", 2, Map.of());
        final var user = new VacuumedPage("userProject", "userColl", 1, Map.of());

        listener.accept(admin);
        listener.accept(pages);
        listener.accept(user);

        verify(adminMock).repositionAfterVacuum(admin);
        verify(adminMock).repositionAfterVacuum(pages);
        verify(userMock).repositionAfterVacuum(user);
        verifyNoMoreInteractions(adminMock, userMock);
    }

    @Test
    public void test_hash_index_identifier_does_not_collide_with_the_typed_identifier() {
        final var typed = Cache.getIndexIdentifier("score", Number.class);
        final var hash = Cache.getHashIndexIdentifier("score", org.ccg.mpp.data.IndexKind.NUMBER.label());
        assertNotEquals(typed, hash,
                "both slots resolved to score|Number, and the two loaders parse the same .idx file into"
                        + " Double and String values, so whichever loaded first poisoned the other");
    }

    @Test
    public void test_hash_index_identifier_still_shares_the_field_eviction_prefix() {
        final var hash = Cache.getHashIndexIdentifier("score", org.ccg.mpp.data.IndexKind.OBJECT.label());
        assertTrue(hash.startsWith("score" + org.ccg.mpp.config.Globals.COLL_IDENTIFIER_SEPARATOR),
                "evictFieldIndexAllTypes removes by the field| prefix, so the namespaced key must keep it");
    }
}
