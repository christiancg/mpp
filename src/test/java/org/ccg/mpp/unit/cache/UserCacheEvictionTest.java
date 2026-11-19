package org.ccg.mpp.unit.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.ccg.mpp.cache.AccessKind;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.CacheResidents;
import org.ccg.mpp.cache.UserCache;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.FieldIndexEntry;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestUtils;
import org.ccg.mpp.utils.ReflectionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class UserCacheEvictionTest {
    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    @Test
    public void evict_entry_from_populated_collection() {
        UserCache cache = new UserCache();
        String projectName = "testProject";
        String collName = "testColl";
        String pk = "testPk";
        String collectionIdentifier = Cache.getCollectionIdentifier(projectName, collName);

        Map<String, DbEntry> collection = new HashMap<>();
        collection.put(pk, new DbEntry());

        final var collectionMap = CacheResidents.documentsByCollection(cache);

        collectionMap.put(collectionIdentifier, collection);

        cache.evictEntry(projectName, collName, pk);

        assertFalse(collectionMap.get(collectionIdentifier).containsKey(pk));
    }

    @Test
    public void evict_entry_from_empty_collection_map() {
        UserCache cache = new UserCache();
        String projectName = "testProject";
        String collName = "testColl";
        String pk = "testPk";

        cache.evictEntry(projectName, collName, pk);

        final var collectionMap = CacheResidents.documentsByCollection(cache);

        assertNull(collectionMap.get(Cache.getCollectionIdentifier(projectName, collName)));
    }

    @Test
    public void test_evict_project_success() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String projectName = "testProject";
        String collectionName = "myCollection";
        String collId = Cache.getCollectionIdentifier(projectName, collectionName);

        PkIndexEntry pkIndexEntry = new PkIndexEntry(projectName, collectionName, "123", 0, 100, 0);
        DbEntry dbEntry = new DbEntry();

        final var typePk = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", typePk);
        final var collectionMap = CacheResidents.documentsByCollection(cache);

        pkIndexMap.put(collId, List.of(pkIndexEntry));
        collectionMap.put(collId, Map.of("key", dbEntry));

        cache.evictProject(projectName);

        assertTrue(pkIndexMap.isEmpty());
        assertTrue(collectionMap.isEmpty());
    }

    // Empty db name is invalid (< 3 chars per constraints); verify it still evicts only its own entries.
    @Test
    public void test_evict_project_empty_string() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String projectName = "";
        String collectionName = "someColl";
        String collId = Cache.getCollectionIdentifier(projectName, collectionName);

        PkIndexEntry pkIndexEntry = new PkIndexEntry(projectName, collectionName, "123", 0, 100, 0);
        DbEntry dbEntry = new DbEntry();

        final var typePk = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", typePk);
        final var collectionMap = CacheResidents.documentsByCollection(cache);

        pkIndexMap.put(collId, List.of(pkIndexEntry));
        collectionMap.put(collId, Map.of("key", dbEntry));

        cache.evictProject(projectName);

        assertTrue(pkIndexMap.isEmpty());
        assertTrue(collectionMap.isEmpty());
    }

    @Test
    public void test_evict_collection_removes_from_pkIndexMap() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String projectName = "testProject";
        String collName = "testColl";
        String collIdentifier = Cache.getCollectionIdentifier(projectName, collName);

        List<PkIndexEntry> pkIndexEntries = new ArrayList<>();

        final var typePk = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", typePk);

        pkIndexMap.put(collIdentifier, pkIndexEntries);

        cache.evictCollection(projectName, collName);

        assertFalse(pkIndexMap.containsKey(collIdentifier));
    }

    @Test
    public void test_evict_collection_with_non_existent_collection()
            throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String projectName = "nonExistentProject";
        String collName = "nonExistentColl";
        String collIdentifier = Cache.getCollectionIdentifier(projectName, collName);

        cache.evictCollection(projectName, collName);

        final var typePk = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", typePk);
        final var collectionMap = CacheResidents.documentsByCollection(cache);

        assertFalse(pkIndexMap.containsKey(collIdentifier));
        assertFalse(collectionMap.containsKey(collIdentifier));
    }

    @Test
    public void test_evict_collection_also_clears_fieldIndexMap() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String projectName = "testProject";
        String collName = "testColl";
        String collIdentifier = Cache.getCollectionIdentifier(projectName, collName);
        String indexIdentifier = Cache.getIndexIdentifier("myField", String.class);

        Map<String, List<FieldIndexEntry<?>>> innerMap = new ConcurrentHashMap<>();
        innerMap.put(indexIdentifier, List.of(new FieldIndexEntry<>(projectName, collName, "val", Set.of("id1"))));
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        TestUtils.getPrivateField(cache, "fieldIndexMap", type).put(collIdentifier, innerMap);

        cache.evictCollection(projectName, collName);

        assertFalse(TestUtils.getPrivateField(cache, "fieldIndexMap", type).containsKey(collIdentifier),
                "evictCollection must remove the collection's field index entries");
    }

    @Test
    public void test_evict_project_also_clears_fieldIndexMap() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String projectName = "testProject";
        String siblingProject = "testProjectSibling";
        String collId1 = Cache.getCollectionIdentifier(projectName, "coll1");
        String collId2 = Cache.getCollectionIdentifier(projectName, "coll2");
        String siblingId = Cache.getCollectionIdentifier(siblingProject, "coll3");
        String indexId = Cache.getIndexIdentifier("f", String.class);

        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", type);
        Map<String, List<FieldIndexEntry<?>>> inner1 = new ConcurrentHashMap<>();
        inner1.put(indexId, List.of(new FieldIndexEntry<>(projectName, "coll1", "v", Set.of("id1"))));
        Map<String, List<FieldIndexEntry<?>>> inner2 = new ConcurrentHashMap<>();
        inner2.put(indexId, List.of(new FieldIndexEntry<>(projectName, "coll2", "v", Set.of("id2"))));
        Map<String, List<FieldIndexEntry<?>>> inner3 = new ConcurrentHashMap<>();
        inner3.put(indexId, List.of(new FieldIndexEntry<>(siblingProject, "coll3", "v", Set.of("id3"))));
        fieldIndexMap.put(collId1, inner1);
        fieldIndexMap.put(collId2, inner2);
        fieldIndexMap.put(siblingId, inner3);

        final var collectionMap = CacheResidents.documentsByCollection(cache);
        collectionMap.put(collId1, new ConcurrentHashMap<>());
        collectionMap.put(collId2, new ConcurrentHashMap<>());

        cache.evictProject(projectName);

        final var remaining = TestUtils.getPrivateField(cache, "fieldIndexMap", type);
        assertFalse(remaining.containsKey(collId1), "coll1 field index must be evicted");
        assertFalse(remaining.containsKey(collId2), "coll2 field index must be evicted");
        assertTrue(remaining.containsKey(siblingId), "sibling db field index must be untouched");
    }

    @Test
    public void test_evictPkIndex_removes_only_pk_for_target_collection() throws Exception {
        UserCache cache = IocContainer.get(UserCache.class);
        final var type = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", type);
        pkIndexMap.put(Cache.getCollectionIdentifier("project1", "c1"),
                List.of(new PkIndexEntry("project1", "c1", "id1", 0, 1, 0)));
        pkIndexMap.put(Cache.getCollectionIdentifier("project1", "c2"),
                List.of(new PkIndexEntry("project1", "c2", "id1", 0, 1, 0)));
        cache.evictPkIndex("project1", "c1");
        assertFalse(pkIndexMap.containsKey(Cache.getCollectionIdentifier("project1", "c1")));
        assertTrue(pkIndexMap.containsKey(Cache.getCollectionIdentifier("project1", "c2")));
    }

    @Test
    public void test_evictFieldIndex_removes_only_target_index() throws Exception {
        UserCache cache = IocContainer.get(UserCache.class);
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", type);
        final Map<String, List<FieldIndexEntry<?>>> indexes = new ConcurrentHashMap<>();
        indexes.put("field|String", List.of(new FieldIndexEntry<>("project1", "c1", "v", Set.of("id1"))));
        indexes.put("other|String", List.of(new FieldIndexEntry<>("project1", "c1", "v", Set.of("id1"))));
        fieldIndexMap.put(Cache.getCollectionIdentifier("project1", "c1"), indexes);
        cache.evictFieldIndex("project1", "c1", "field|String");
        assertFalse(indexes.containsKey("field|String"));
        assertTrue(indexes.containsKey("other|String"));
    }

    @Test
    public void test_evictFieldIndex_removes_collection_entry_when_last_index_evicted() throws Exception {
        UserCache cache = IocContainer.get(UserCache.class);
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", type);
        final Map<String, List<FieldIndexEntry<?>>> indexes = new ConcurrentHashMap<>();
        indexes.put("field|String", List.of(new FieldIndexEntry<>("project1", "c1", "v", Set.of("id1"))));
        fieldIndexMap.put(Cache.getCollectionIdentifier("project1", "c1"), indexes);
        cache.evictFieldIndex("project1", "c1", "field|String");
        assertFalse(fieldIndexMap.containsKey(Cache.getCollectionIdentifier("project1", "c1")));
    }

    @Test
    public void test_evictCollectionDocuments_removes_only_documents_not_pk() throws Exception {
        UserCache cache = IocContainer.get(UserCache.class);
        final var pkType = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", pkType);
        final var collectionMap = CacheResidents.documentsByCollection(cache);
        pkIndexMap.put(Cache.getCollectionIdentifier("project1", "c1"),
                List.of(new PkIndexEntry("project1", "c1", "id1", 0, 1, 0)));
        collectionMap.put(Cache.getCollectionIdentifier("project1", "c1"), new ConcurrentHashMap<>());
        cache.evictCollectionDocuments("project1", "c1");
        assertTrue(pkIndexMap.containsKey(Cache.getCollectionIdentifier("project1", "c1")));
        assertFalse(collectionMap.containsKey(Cache.getCollectionIdentifier("project1", "c1")));
    }

    @Test
    public void test_evictProject_does_not_evict_sibling_project() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        final var collIdFoo = Cache.getCollectionIdentifier("foo", "coll1");
        final var collIdFoobar = Cache.getCollectionIdentifier("foobar", "coll2");

        final var typePk = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", typePk);
        final var collectionMap = CacheResidents.documentsByCollection(cache);

        pkIndexMap.put(collIdFoo, List.of(new PkIndexEntry("foo", "coll1", "1", 0, 10, 0)));
        pkIndexMap.put(collIdFoobar, List.of(new PkIndexEntry("foobar", "coll2", "2", 0, 10, 0)));
        collectionMap.put(collIdFoo, new ConcurrentHashMap<>());
        collectionMap.put(collIdFoobar, new ConcurrentHashMap<>());

        cache.evictProject("foo");

        assertFalse(pkIndexMap.containsKey(collIdFoo), "foo|coll1 should have been evicted");
        assertFalse(collectionMap.containsKey(collIdFoo), "foo|coll1 should have been evicted");
        assertTrue(pkIndexMap.containsKey(collIdFoobar), "foobar|coll2 must not be evicted");
        assertTrue(collectionMap.containsKey(collIdFoobar), "foobar|coll2 must not be evicted");
    }

    @Test
    public void test_evict_project_removes_a_pk_index_with_no_cached_documents()
            throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        final var collId = Cache.getCollectionIdentifier("testProject", "coll1");
        final var typePk = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", typePk);
        pkIndexMap.put(collId, List.of(new PkIndexEntry("testProject", "coll1", "1", 0, 10, 0)));

        cache.evictProject("testProject");

        assertFalse(pkIndexMap.containsKey(collId), "a pk index with no cached documents must still be evicted");
    }

    @Test
    public void test_evict_project_removes_a_field_index_with_no_cached_documents()
            throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        final var collId = Cache.getCollectionIdentifier("testProject", "coll1");
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", type);
        Map<String, List<FieldIndexEntry<?>>> inner = new ConcurrentHashMap<>();
        inner.put(Cache.getIndexIdentifier("f", String.class),
                List.of(new FieldIndexEntry<>("testProject", "coll1", "v", Set.of("id1"))));
        fieldIndexMap.put(collId, inner);

        cache.evictProject("testProject");

        assertFalse(fieldIndexMap.containsKey(collId), "a field index with no cached documents must still be evicted");
    }

    @Test
    public void test_evict_project_after_document_only_eviction() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        final var collId = Cache.getCollectionIdentifier("testProject", "coll1");
        final var typePk = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", typePk);
        final var collectionMap = CacheResidents.documentsByCollection(cache);
        final var typeField = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", typeField);
        pkIndexMap.put(collId, List.of(new PkIndexEntry("testProject", "coll1", "1", 0, 10, 0)));
        CacheResidents.residentDocument(cache, collId, DbEntry.fromJsonObject("testProject", "coll1", documentD1()));
        fieldIndexMap.put(collId, new ConcurrentHashMap<>());

        cache.evictCollectionDocuments("testProject", "coll1");
        cache.evictProject("testProject");

        assertFalse(pkIndexMap.containsKey(collId));
        assertFalse(collectionMap.containsKey(collId));
        assertEquals(0L, CacheResidents.footprint(cache, AccessKind.COLLECTION));
        assertFalse(fieldIndexMap.containsKey(collId));
    }

    private static JsonObject documentD1() {
        final var data = new JsonObject();
        data.addProperty("_id", "d1");
        return data;
    }
}
