package org.ccg.mpp.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.ccg.mpp.cache.AdminCache;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.ccg.mpp.utils.ReflectionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AdminCacheTest {
    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    @Test
    public void test_load_admin_data_populates_maps_correctly()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();

        cache.loadAdminData();

        final var jsonProject = new JsonObject();
        jsonProject.add(Globals.PK_FIELD, "test_create");
        final var arrProject = new JsonArray();
        arrProject.add("test_create_collection");
        jsonProject.add("collections", arrProject);
        final var adminProjectEntry = AdminProjectEntry.fromJsonObject(jsonProject);
        final var pkIndexEntry = new PkIndexEntry(Globals.ADMIN_PROJECT_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME,
                "test_create", 0, 100, 0);
        cache.putAdminProjectEntry(adminProjectEntry, pkIndexEntry);

        final var jsonColl = new JsonObject();
        jsonColl.add(Globals.PK_FIELD, "test_create");
        final var arrColl = new JsonArray();
        arrColl.add("test_index");
        jsonColl.add("indexes", arrColl);
        jsonColl.add("entryCount", new JsonNumber(0));
        final var adminCollEntry = AdminCollEntry.fromJsonObject(jsonColl);
        cache.putAdminCollectionEntry(adminCollEntry, pkIndexEntry);

        final var typeProjects = new ReflectionUtils.TypeToken<Map<String, AdminProjectEntry>>() {
        };
        final var projects = TestUtils.getPrivateField(cache, "projects", typeProjects);
        final var typeColl = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", typeColl);

        Assertions.assertFalse(projects.isEmpty());
        Assertions.assertFalse(collections.isEmpty());
    }

    @Test
    public void test_load_admin_data_when_file_system_is_empty()
            throws IOException, IllegalAccessException, NoSuchFieldException {
        AdminCache cache = new AdminCache();

        cache.loadAdminData();

        final var typeProjects = new ReflectionUtils.TypeToken<Map<String, AdminProjectEntry>>() {
        };
        final var projects = TestUtils.getPrivateField(cache, "projects", typeProjects);
        final var typeColl = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", typeColl);
        Assertions.assertTrue(projects.isEmpty());
        Assertions.assertTrue(collections.isEmpty());
    }

    @Test
    public void test_retrieve_pk_index_entry_existing_dbname() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        PkIndexEntry expectedEntry = new PkIndexEntry("testProject", "testCollection", "testValue", 1L, 100L, 0);

        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var projectsPkIndex = TestUtils.getPrivateField(cache, "projectsPkIndex", typeCollPk);

        projectsPkIndex.put("testProject", expectedEntry);

        PkIndexEntry result = cache.getPkIndexAdminProjectEntry("testProject");

        assertNotNull(result);
        assertEquals(expectedEntry, result);
    }

    @Test
    public void test_retrieve_pk_index_entry_empty_dbname() {
        AdminCache cache = new AdminCache();

        PkIndexEntry result = cache.getPkIndexAdminProjectEntry("");

        assertNull(result);
    }

    @Test
    public void test_add_valid_pk_index_entry() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        PkIndexEntry entry = new PkIndexEntry("project1", "coll1", "value1", 0L, 100L, 0);
        cache.putPkIndexAdminProjectEntry(entry);

        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var projectsPkIndex = TestUtils.getPrivateField(cache, "projectsPkIndex", typeCollPk);

        assertEquals(entry, projectsPkIndex.get("value1"));
    }

    @Test
    public void test_retrieve_existing_admin_project_entry() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        AdminProjectEntry entry = new AdminProjectEntry("testProject");

        final var typeProjects = new ReflectionUtils.TypeToken<Map<String, AdminProjectEntry>>() {
        };
        final var projects = TestUtils.getPrivateField(cache, "projects", typeProjects);

        projects.put("testProject", entry);

        AdminProjectEntry result = cache.getAdminProjectEntry("testProject");

        assertNotNull(result);
        assertEquals("testProject", result.get_id());
    }

    @Test
    public void test_empty_project_name() {
        AdminCache cache = new AdminCache();

        AdminProjectEntry result = cache.getAdminProjectEntry("");

        assertNull(result);
    }

    @Test
    public void test_retrieve_existing_pk_index_entry() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        PkIndexEntry expectedEntry = new PkIndexEntry("projectName", "collName", "value", 1L, 100L, 0);

        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var collectionsPkIndex = TestUtils.getPrivateField(cache, "collectionsPkIndex", typeCollPk);

        collectionsPkIndex.put("validCollId", expectedEntry);

        PkIndexEntry result = cache.getPkIndexAdminCollEntry("validCollId");

        assertNotNull(result);
        assertEquals(expectedEntry, result);
    }

    @Test
    public void test_adds_pk_index_entry_to_map() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        PkIndexEntry entry = new PkIndexEntry("project1", "coll1", "value1", 1L, 100L, 0);
        cache.putPkIndexAdminCollEntry(entry);

        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var collectionsPkIndex = TestUtils.getPrivateField(cache, "collectionsPkIndex", typeCollPk);

        assertEquals(entry, collectionsPkIndex.get("value1"));
    }

    @Test
    public void test_retrieves_admin_coll_entry_when_exists_in_cache()
            throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        String projectName = "testProject";
        String collName = "testCollection";
        AdminCollEntry expectedEntry = new AdminCollEntry(projectName, collName);

        final var typeColl = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", typeColl);

        collections.put(Cache.getCollectionIdentifier(projectName, collName), expectedEntry);

        AdminCollEntry result = cache.getAdminCollectionEntry(projectName, collName);

        assertNotNull(result);
        assertEquals(expectedEntry, result);
    }

    @Test
    public void test_handles_null_values_gracefully() {
        AdminCache cache = new AdminCache();

        AdminCollEntry result1 = cache.getAdminCollectionEntry(null, "testCollection");
        AdminCollEntry result2 = cache.getAdminCollectionEntry("testProject", null);
        AdminCollEntry result3 = cache.getAdminCollectionEntry(null, null);

        assertNull(result1);
        assertNull(result2);
        assertNull(result3);
    }

    // A collection dropped mid-event is no longer cached; this must answer empty rather than throw, so
    // background maintenance becomes a clean no-op.
    @Test
    public void test_get_indexes_for_missing_collection_returns_empty() {
        AdminCache cache = new AdminCache();

        Set<String> result = cache.getIndexesForCollection("goneProject", "goneColl");

        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertFalse(cache.hasIndex("goneProject", "goneColl", "anyField"));
    }

    @Test
    public void test_get_indexes_for_existing_collection_returns_indexes()
            throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        String projectName = "testProject";
        String collName = "testCollection";
        AdminCollEntry entry = new AdminCollEntry(projectName, collName);
        entry.setIndexes(Set.of("status", "score"));

        final var typeColl = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        TestUtils.getPrivateField(cache, "collections", typeColl)
                .put(Cache.getCollectionIdentifier(projectName, collName), entry);

        assertEquals(Set.of("status", "score"), cache.getIndexesForCollection(projectName, collName));
        assertTrue(cache.hasIndex(projectName, collName, "status"));
        assertFalse(cache.hasIndex(projectName, collName, "missing"));
    }

    @Test
    public void test_successfully_adds_entries_to_maps() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        AdminProjectEntry adminProjectEntry = new AdminProjectEntry("testProject");
        PkIndexEntry pkIndexEntry = new PkIndexEntry("testProject", "testCollection", "testValue", 1L, 100L, 0);

        cache.putAdminProjectEntry(adminProjectEntry, pkIndexEntry);

        final var typeProjects = new ReflectionUtils.TypeToken<Map<String, AdminProjectEntry>>() {
        };
        final var projects = TestUtils.getPrivateField(cache, "projects", typeProjects);
        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var projectsPkIndex = TestUtils.getPrivateField(cache, "projectsPkIndex", typeCollPk);

        assertEquals(adminProjectEntry, projects.get("testProject"));
        assertEquals(pkIndexEntry, projectsPkIndex.get("testProject"));
    }

    @Test
    public void test_remove_entry_when_dbname_exists() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        String projectName = "testProject";
        AdminProjectEntry adminProjectEntry = new AdminProjectEntry(projectName);
        PkIndexEntry pkIndexEntry = new PkIndexEntry(projectName, "testCollection", "testValue", 1L, 100L, 0);

        final var typeProjects = new ReflectionUtils.TypeToken<Map<String, AdminProjectEntry>>() {
        };
        final var projects = TestUtils.getPrivateField(cache, "projects", typeProjects);
        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var projectsPkIndex = TestUtils.getPrivateField(cache, "projectsPkIndex", typeCollPk);

        projects.put(projectName, adminProjectEntry);
        projectsPkIndex.put(projectName, pkIndexEntry);

        cache.removeAdminProjectEntry(projectName);

        assertFalse(projects.containsKey(projectName));
        assertFalse(projectsPkIndex.containsKey(projectName));
    }

    @Test
    public void test_successfully_adds_entries_to_collections_maps()
            throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        AdminCollEntry dbEntry = new AdminCollEntry("testProject", "testColl");
        PkIndexEntry indexEntry = new PkIndexEntry("testProject", "testColl", "testValue", 1L, 100L, 0);

        cache.putAdminCollectionEntry(dbEntry, indexEntry);

        final var typeColl = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", typeColl);
        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var collectionsPkIndex = TestUtils.getPrivateField(cache, "collectionsPkIndex", typeCollPk);

        assertEquals(dbEntry, collections.get(dbEntry.get_id()));
        assertEquals(indexEntry, collectionsPkIndex.get(dbEntry.get_id()));
    }

    @Test
    public void test_remove_existing_collection_identifier() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        String collIdentifier = "testCollection";
        AdminCollEntry adminCollEntry = new AdminCollEntry(TestGlobals.PROJECT, collIdentifier);
        PkIndexEntry pkIndexEntry = new PkIndexEntry(TestGlobals.PROJECT, collIdentifier, "testValue", 1L, 100L, 0);

        final var typeColl = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", typeColl);
        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var collectionsPkIndex = TestUtils.getPrivateField(cache, "collectionsPkIndex", typeCollPk);

        collections.put(collIdentifier, adminCollEntry);
        collectionsPkIndex.put(collIdentifier, pkIndexEntry);

        cache.removeAdminCollEntry(collIdentifier);

        assertFalse(collections.containsKey(collIdentifier));
        assertFalse(collectionsPkIndex.containsKey(collIdentifier));
    }

    @Test
    public void test_remove_nonexistent_collection_identifier() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        String collIdentifier = "nonExistentCollection";

        cache.removeAdminCollEntry(collIdentifier);

        final var typeColl = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", typeColl);
        final var typeCollPk = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var collectionsPkIndex = TestUtils.getPrivateField(cache, "collectionsPkIndex", typeCollPk);

        assertFalse(collections.containsKey(collIdentifier));
        assertFalse(collectionsPkIndex.containsKey(collIdentifier));
    }

    @Test
    public void test_field_index_exists() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        String projectName = "testProject";
        String collName = "testCollection";
        String fieldName = "testField";

        Set<String> indexes = new HashSet<>(Arrays.asList("testField", "anotherField"));

        final var type = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", type);

        var coll = new AdminCollEntry(projectName, collName, indexes);

        collections.put(Cache.getCollectionIdentifier(projectName, collName), coll);

        boolean result = cache.hasIndex(projectName, collName, fieldName);
        assertTrue(result);
    }

    @Test
    public void test_retrieve_indexes_for_valid_collection_identifier()
            throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        AdminCollEntry adminCollEntry = mock(AdminCollEntry.class);
        Set<String> indexes = new HashSet<>();
        indexes.add("index1");
        indexes.add("index2");
        when(adminCollEntry.getIndexes()).thenReturn(indexes);

        final var type = new ReflectionUtils.TypeToken<Map<String, AdminCollEntry>>() {
        };
        final var collections = TestUtils.getPrivateField(cache, "collections", type);

        collections.put("testProject|testColl", adminCollEntry);
        Set<String> expectedIndexes = new HashSet<>();
        expectedIndexes.add("index1");
        expectedIndexes.add("index2");
        Set<String> actualIndexes = cache.getIndexesForCollection("testProject", "testColl");
        assertEquals(expectedIndexes, actualIndexes);
    }

    @Test
    public void test_get_collection_names_for_project() throws IOException, InterruptedException {
        TestUtils.createTestProjectAndCollection();
        AdminCache cache = IocContainer.get(AdminCache.class);
        List<String> names = cache.getCollectionNamesForProject(TestGlobals.PROJECT);
        assertNotNull(names);
        assertTrue(names.contains(TestGlobals.COLL));
    }

    @Test
    public void test_get_collection_names_excludes_other_projects() throws IOException, InterruptedException {
        TestUtils.createTestProjectAndCollection();
        AdminCache cache = IocContainer.get(AdminCache.class);
        List<String> names = cache.getCollectionNamesForProject("someOtherProject");
        assertNotNull(names);
        assertTrue(names.isEmpty());
    }

    @Test
    public void test_load_admin_data_with_existing_users_populates_users_map() throws Exception {
        final var userEntry = new org.ccg.mpp.data.admin.AdminUserEntry("cachetest_user", "hash", false,
                new java.util.HashSet<>(), new java.util.HashMap<>(), new java.util.HashMap<>());
        org.ccg.mpp.ops.AdminOperationHelper.saveUserEntry(userEntry);

        AdminCache cache = IocContainer.get(AdminCache.class);
        TestUtils.setPrivateField(cache, "users", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "usersPkIndex", new ConcurrentHashMap<>());

        cache.loadAdminData();

        assertNotNull(cache.getAdminUserEntry("cachetest_user"));
    }

    @Test
    public void test_load_admin_data_with_existing_projects_and_collections() throws Exception {
        TestUtils.createTestProjectAndCollection();

        AdminCache cache = IocContainer.get(AdminCache.class);
        TestUtils.setPrivateField(cache, "projects", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "collections", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "projectsPkIndex", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "collectionsPkIndex", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(TestUtils.pageCacheOf(cache), "pages", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(TestUtils.pageCacheOf(cache), "pagesPkIndexes", new ConcurrentHashMap<>());

        cache.loadAdminData();

        assertNotNull(cache.getAdminProjectEntry(TestGlobals.PROJECT));
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    @Test
    public void test_collection_usage_pk_index_round_trip() {
        AdminCache cache = new AdminCache();
        final var pk = new PkIndexEntry(Globals.ADMIN_PROJECT_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME, "usage-id", 0,
                10, 0);
        cache.putPkIndexCollectionUsage(pk);
        assertEquals(pk, cache.getPkIndexCollectionUsage("usage-id"));
        assertTrue(cache.getCollectionUsagePkIndexes().containsKey("usage-id"));
        cache.removePkIndexCollectionUsage("usage-id");
        assertNull(cache.getPkIndexCollectionUsage("usage-id"));
    }
}
