package org.ccg.mpp.unit.data.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.junit.jupiter.api.Test;

public class AdminProjectEntryTest {
    @Test
    public void test_admin_project_entry_initializes_with_default_collections() {
        AdminProjectEntry entry = new AdminProjectEntry("testProject");
        assertEquals("admin", entry.getProjectName());
        assertEquals("projects", entry.getCollectionName());
        assertEquals("testProject", entry.get_id());
        assertTrue(entry.getCollections().isEmpty());
        JsonObject data = entry.getData();
        assertTrue(data.has("collections"));
        assertTrue(data.get("collections").asJsonArray().isEmpty());
    }

    @Test
    public void test_initializes_with_given_project_name_and_collections() {
        String projectName = "testProject";
        List<String> collections = List.of("collection1", "collection2");
        AdminProjectEntry entry = new AdminProjectEntry(projectName, collections);

        assertEquals(projectName, entry.get_id());
        assertEquals(collections, entry.getCollections());
        assertEquals(Globals.ADMIN_PROJECT_NAME, entry.getProjectName());
        assertEquals(Globals.ADMIN_PROJECTS_COLLECTION_NAME, entry.getCollectionName());

        JsonObject data = entry.getData();
        assertTrue(data.has("collections"));
        JsonArray jsonArray = data.get("collections").asJsonArray();
        assertEquals(2, jsonArray.size());
        assertEquals("collection1", jsonArray.get(0).asJsonString().getValue());
        assertEquals("collection2", jsonArray.get(1).asJsonString().getValue());
    }

    @Test
    public void test_handles_empty_collections_list() {
        String projectName = "testProject";
        List<String> collections = new ArrayList<>();
        AdminProjectEntry entry = new AdminProjectEntry(projectName, collections);

        assertEquals(projectName, entry.get_id());
        assertTrue(entry.getCollections().isEmpty());
        assertEquals(Globals.ADMIN_PROJECT_NAME, entry.getProjectName());
        assertEquals(Globals.ADMIN_PROJECTS_COLLECTION_NAME, entry.getCollectionName());

        JsonObject data = entry.getData();
        assertTrue(data.has("collections"));
        JsonArray jsonArray = data.get("collections").asJsonArray();
        assertTrue(jsonArray.isEmpty());
    }

    @Test
    public void test_valid_json_object_conversion() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.add(Globals.PK_FIELD, new JsonString("testProject"));
        JsonArray collectionsArray = new JsonArray();
        collectionsArray.add(new JsonString("collection1"));
        collectionsArray.add(new JsonString("collection2"));
        jsonObject.add("collections", collectionsArray);

        AdminProjectEntry entry = AdminProjectEntry.fromJsonObject(jsonObject);

        assertEquals("testProject", entry.get_id());
        assertEquals(List.of("collection1", "collection2"), entry.getCollections());
        assertEquals(Globals.ADMIN_PROJECT_NAME, entry.getProjectName());
        assertEquals(Globals.ADMIN_PROJECTS_COLLECTION_NAME, entry.getCollectionName());
    }

    @Test
    public void test_no_collections_field() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.add(Globals.PK_FIELD, new JsonString("testProject"));

        AdminProjectEntry entry = AdminProjectEntry.fromJsonObject(jsonObject);

        assertEquals("testProject", entry.get_id());
        assertTrue(entry.getCollections().isEmpty());
        assertEquals(Globals.ADMIN_PROJECT_NAME, entry.getProjectName());
        assertEquals(Globals.ADMIN_PROJECTS_COLLECTION_NAME, entry.getCollectionName());
    }

    @Test
    public void test_update_collections_with_new_list() {
        AdminProjectEntry entry = new AdminProjectEntry("testProject");
        List<String> newCollections = List.of("collection1", "collection2");
        entry.setCollections(newCollections);

        JsonObject data = entry.getData();
        JsonArray collectionsArray = data.get("collections").asJsonArray();

        assertEquals(2, collectionsArray.size());
        assertTrue(collectionsArray.contains(new JsonString("collection1")));
        assertTrue(collectionsArray.contains(new JsonString("collection2")));
    }

    @Test
    public void test_handle_empty_collections_list() {
        AdminProjectEntry entry = new AdminProjectEntry("testProject");
        List<String> emptyCollections = new ArrayList<>();
        entry.setCollections(emptyCollections);

        JsonObject data = entry.getData();
        JsonArray collectionsArray = data.get("collections").asJsonArray();

        assertEquals(0, collectionsArray.size());
    }

    @Test
    public void test_setters_and_getters() {
        AdminProjectEntry entry = new AdminProjectEntry("testProject");
        entry.setCollections(List.of("collection1", "collection2"));
        assertEquals(List.of("collection1", "collection2"), entry.getCollections());
    }

    @Test
    public void test_equals() {
        AdminProjectEntry entry = new AdminProjectEntry("testProject");
        AdminProjectEntry entry2 = new AdminProjectEntry("testProject");
        assertEquals(entry, entry2);
    }

    @Test
    public void test_hashcode() {
        AdminProjectEntry entry = new AdminProjectEntry("testProject");
        AdminProjectEntry entry2 = new AdminProjectEntry("testProject");
        assertEquals(entry.hashCode(), entry2.hashCode());
    }

    @Test
    public void test_toString() {
        AdminProjectEntry entry = new AdminProjectEntry("testProject");
        AdminProjectEntry entry2 = new AdminProjectEntry("testProject");
        assertEquals(entry.toString(), entry2.toString());
    }
}
