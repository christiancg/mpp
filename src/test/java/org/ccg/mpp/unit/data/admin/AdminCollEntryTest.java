package org.ccg.mpp.unit.data.admin;

import static org.ccg.mpp.test.EqualityChecks.assertDiffers;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.junit.jupiter.api.Test;

public class AdminCollEntryTest {
    private static final String INDEXES_FIELD_NAME = "indexes";

    @Test
    public void test_default_constructor_sets_admin_names() {
        AdminCollEntry entry = new AdminCollEntry("db", "coll");
        assertEquals(Globals.ADMIN_PROJECT_NAME, entry.getProjectName());
        assertEquals(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, entry.getCollectionName());
        assertEquals(Set.of(), entry.getIndexes());
        assertTrue(entry.get_id().contains("db"));
        assertTrue(entry.get_id().contains("coll"));
    }

    @Test
    public void test_constructs_admin_coll_entry_with_indexes() {
        String projectName = "testProject";
        String collName = "testCollection";
        Set<String> indexes = new HashSet<>();
        indexes.add("index1");
        indexes.add("index2");

        AdminCollEntry entry = new AdminCollEntry(projectName, collName, indexes);

        assertEquals(Globals.ADMIN_PROJECT_NAME, entry.getProjectName());
        assertEquals(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, entry.getCollectionName());
        assertEquals(Cache.getCollectionIdentifier(projectName, collName), entry.get_id());
        assertEquals(indexes, entry.getIndexes());
    }

    @Test
    public void test_handles_empty_indexes_set() {
        String projectName = "testProject";
        String collName = "testCollection";
        Set<String> indexes = new HashSet<>();

        AdminCollEntry entry = new AdminCollEntry(projectName, collName, indexes);

        assertEquals(Globals.ADMIN_PROJECT_NAME, entry.getProjectName());
        assertEquals(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, entry.getCollectionName());
        assertEquals(Cache.getCollectionIdentifier(projectName, collName), entry.get_id());
        assertTrue(entry.getIndexes().isEmpty());
    }

    @Test
    public void test_from_json_object_valid_input() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(Globals.PK_FIELD, "test_id");
        JsonArray indexesArray = new JsonArray();
        indexesArray.add("index1");
        indexesArray.add("index2");
        jsonObject.add(INDEXES_FIELD_NAME, indexesArray);

        AdminCollEntry entry = AdminCollEntry.fromJsonObject(jsonObject);

        assertEquals("test_id", entry.get_id());
        assertEquals(Set.of("index1", "index2"), entry.getIndexes());
        assertEquals(Globals.ADMIN_PROJECT_NAME, entry.getProjectName());
        assertEquals(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, entry.getCollectionName());
    }

    @Test
    public void test_update_indexes_with_new_set() {
        AdminCollEntry entry = new AdminCollEntry("testProject", "testCollection");
        Set<String> newIndexes = new HashSet<>(Arrays.asList("index1", "index2"));
        entry.setIndexes(newIndexes);

        JsonObject data = entry.getData();
        JsonArray indexesArray = data.get("indexes").asJsonArray();

        assertEquals(2, indexesArray.size());
        assertTrue(indexesArray.contains(new JsonString("index1")));
        assertTrue(indexesArray.contains(new JsonString("index2")));
    }

    @Test
    public void test_equals_same_instance() {
        AdminCollEntry entry = new AdminCollEntry("db", "coll");
        assertEquals(entry, entry);
    }

    @Test
    public void test_equals_symmetric() {
        AdminCollEntry entry1 = new AdminCollEntry("db", "coll");
        AdminCollEntry entry2 = new AdminCollEntry("db", "coll");
        assertEquals(entry1, entry2);
        assertEquals(entry2, entry1);
    }

    @Test
    public void test_equals_null_returns_false() {
        AdminCollEntry entry = new AdminCollEntry("db", "coll");
        assertNotEquals(null, entry);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        AdminCollEntry entry = new AdminCollEntry("db", "coll");
        assertDiffers(entry, "notAnEntry");
    }

    @Test
    public void test_equals_different_indexes_returns_false() {
        AdminCollEntry entry1 = new AdminCollEntry("db", "coll", new HashSet<>(List.of("idx1")));
        AdminCollEntry entry2 = new AdminCollEntry("db", "coll", new HashSet<>(List.of("idx2")));
        assertNotEquals(entry1, entry2);
    }

    @Test
    public void test_hashCode_same_values_equal() {
        AdminCollEntry entry1 = new AdminCollEntry("db", "coll");
        AdminCollEntry entry2 = new AdminCollEntry("db", "coll");
        assertEquals(entry1.hashCode(), entry2.hashCode());
    }

    @Test
    public void test_hashCode_different_indexes_differs() {
        AdminCollEntry entry1 = new AdminCollEntry("db", "coll", new HashSet<>(List.of("idx1")));
        AdminCollEntry entry2 = new AdminCollEntry("db", "coll", new HashSet<>(List.of("idx2")));
        assertNotEquals(entry1.hashCode(), entry2.hashCode());
    }

    @Test
    public void test_toString_not_null() {
        AdminCollEntry entry = new AdminCollEntry("db", "coll");
        assertNotNull(entry.toString());
    }
}
