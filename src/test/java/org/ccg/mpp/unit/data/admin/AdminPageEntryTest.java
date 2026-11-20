package org.ccg.mpp.unit.data.admin;

import static org.ccg.mpp.test.EqualityChecks.assertDiffers;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminPageEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.junit.jupiter.api.Test;

public class AdminPageEntryTest {

    @Test
    public void test_constructor_sets_correct_collection_name() {
        AdminPageEntry entry = new AdminPageEntry("myProject", "myColl");
        assertEquals(Globals.ADMIN_PAGES_PROJECT_NAME, entry.getProjectName());
        assertEquals(String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, "myProject", "myColl"),
                entry.getCollectionName());
    }

    @Test
    public void test_id_follows_deterministic_scheme() {
        AdminPageEntry entry = new AdminPageEntry("myProject", "myColl", 3L);
        assertEquals(
                "myProject" + Globals.COLL_IDENTIFIER_SEPARATOR + "myColl" + Globals.COLL_IDENTIFIER_SEPARATOR + "3",
                entry.get_id());
        assertEquals(3L, entry.getPage());
    }

    @Test
    public void test_setters_sync_data() {
        AdminPageEntry entry = new AdminPageEntry("myProject", "myColl", 0L);
        entry.setEntryCount(7);
        entry.setPageSize(1024L);
        entry.setPage(2L);

        assertEquals(7, entry.getEntryCount());
        assertEquals(1024L, entry.getPageSize());
        assertEquals(2L, entry.getPage());

        JsonObject data = entry.getData();
        assertNotNull(data);
        assertEquals(7, data.get("entryCount").asJsonNumber().getValue().intValue());
        assertEquals(1024L, data.get("size").asJsonNumber().getValue().longValue());
        assertEquals(2L, data.get("page").asJsonNumber().getValue().longValue());
    }

    @Test
    public void test_from_json_object_populates_fields() {
        JsonObject obj = new JsonObject();
        obj.addProperty(Globals.PK_FIELD, "myProject|myColl|5");
        obj.addProperty("page", 5L);
        obj.addProperty("entryCount", 12);
        obj.addProperty("size", 4096L);

        AdminPageEntry entry = AdminPageEntry.fromJsonObject("myProject", "myColl", obj);
        assertEquals("myProject|myColl|5", entry.get_id());
        assertEquals(5L, entry.getPage());
        assertEquals(12, entry.getEntryCount());
        assertEquals(4096L, entry.getPageSize());
    }

    @Test
    public void test_equals_symmetric() {
        AdminPageEntry entry1 = new AdminPageEntry("db", "coll", 1L);
        AdminPageEntry entry2 = new AdminPageEntry("db", "coll", 1L);
        assertEquals(entry1, entry2);
        assertEquals(entry2, entry1);
    }

    @Test
    public void test_equals_null_returns_false() {
        AdminPageEntry entry = new AdminPageEntry("db", "coll", 0L);
        assertNotEquals(null, entry);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        AdminPageEntry entry = new AdminPageEntry("db", "coll", 0L);
        assertDiffers(entry, "notAnEntry");
    }

    @Test
    public void test_equals_different_page_returns_false() {
        AdminPageEntry entry1 = new AdminPageEntry("db", "coll", 0L);
        AdminPageEntry entry2 = new AdminPageEntry("db", "coll", 1L);
        assertNotEquals(entry1, entry2);
    }

    @Test
    public void test_equals_different_entryCount_returns_false() {
        AdminPageEntry entry1 = new AdminPageEntry("db", "coll", 0L);
        AdminPageEntry entry2 = new AdminPageEntry("db", "coll", 0L);
        entry1.setEntryCount(5);
        entry2.setEntryCount(10);
        assertNotEquals(entry1, entry2);
    }

    @Test
    public void test_hashCode_same_values_equal() {
        AdminPageEntry entry1 = new AdminPageEntry("db", "coll", 2L);
        AdminPageEntry entry2 = new AdminPageEntry("db", "coll", 2L);
        assertEquals(entry1.hashCode(), entry2.hashCode());
    }

    @Test
    public void test_hashCode_different_page_differs() {
        AdminPageEntry entry1 = new AdminPageEntry("db", "coll", 0L);
        AdminPageEntry entry2 = new AdminPageEntry("db", "coll", 1L);
        assertNotEquals(entry1.hashCode(), entry2.hashCode());
    }

    @Test
    public void test_toString_not_null() {
        AdminPageEntry entry = new AdminPageEntry("db", "coll", 0L);
        assertNotNull(entry.toString());
    }

    @Test
    public void test_each_setter_rewrites_only_its_field_and_the_encoded_line_follows() {
        AdminPageEntry entry = new AdminPageEntry("myProject", "myColl", 4L);
        entry.setEntryCount(2);
        entry.setPageSize(100L);
        final var before = new String(entry.encodeLine(), StandardCharsets.UTF_8);

        entry.setPageSize(250L);
        entry.setEntryCount(3);

        final var after = new String(entry.encodeLine(), StandardCharsets.UTF_8);
        assertTrue(before.contains("\"size\":100"), before);
        assertTrue(after.contains("\"size\":250") && after.contains("\"entryCount\":3") && after.contains("\"page\":4"),
                after);
        assertEquals(250L, entry.getData().get("size").asJsonNumber().getValue().longValue());
    }
}
