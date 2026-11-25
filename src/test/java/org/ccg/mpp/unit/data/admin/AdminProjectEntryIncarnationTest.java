package org.ccg.mpp.unit.data.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.junit.jupiter.api.Test;

public class AdminProjectEntryIncarnationTest {
    @Test
    public void test_the_incarnation_survives_the_stored_form() {
        final var entry = new AdminProjectEntry("shop", new ArrayList<>(List.of("orders")), new ArrayList<>(), 42L);
        assertEquals("42", entry.getData().get("incarnation").asJsonString().getValue());
        final var read = AdminProjectEntry.fromJsonObject(entry.getData());
        assertEquals(42L, read.getIncarnation());
        assertEquals(entry, read);
        assertEquals(entry.hashCode(), read.hashCode());
        assertTrue(read.toString().contains("incarnation=42"));
    }

    @Test
    public void test_a_project_without_one_reads_as_zero_and_stores_none() {
        final var entry = new AdminProjectEntry("old", new ArrayList<>(), new ArrayList<>());
        assertEquals(0L, entry.getIncarnation());
        assertFalse(entry.getData().has("incarnation"));
        assertEquals(0L, AdminProjectEntry.fromJsonObject(entry.getData()).getIncarnation());
        assertNotEquals(new AdminProjectEntry("old", new ArrayList<>(), new ArrayList<>(), 1L), entry);
    }
}
