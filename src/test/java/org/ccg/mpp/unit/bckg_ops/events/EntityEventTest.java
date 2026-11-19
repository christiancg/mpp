package org.ccg.mpp.unit.bckg_ops.events;

import static org.ccg.mpp.test.EqualityChecks.assertDiffers;
import static org.junit.jupiter.api.Assertions.*;

import org.ccg.mpp.bckg_ops.events.EntityEvent;
import org.ccg.mpp.bckg_ops.events.EntityRef;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.junit.jupiter.api.Test;

public class EntityEventTest {
    @Test
    public void test_create_entity_event_with_valid_parameters() {
        EventType type = EventType.CREATED;
        String projectName = "testProject";
        String collName = "testCollection";
        JsonObject jsonObject = new JsonObject();
        DbEntry dbEntry = DbEntry.fromJsonObject(projectName, collName, jsonObject);

        EntityEvent entityEvent = new EntityEvent(type, projectName, collName, dbEntry);

        assertEquals(type, entityEvent.getType());
        assertEquals(projectName, entityEvent.getProjectName());
        assertEquals(collName, entityEvent.getCollName());
        assertEquals(EntityRef.of(dbEntry), entityEvent.getEntity());
    }

    @Test
    public void test_getters() {
        EventType type = EventType.CREATED;
        String projectName = "testProject";
        String collName = "testCollection";
        JsonObject jsonObject = new JsonObject();
        DbEntry dbEntry = DbEntry.fromJsonObject(projectName, collName, jsonObject);
        EntityEvent entityEvent = new EntityEvent(type, projectName, collName, dbEntry);
        assertEquals("testProject", entityEvent.getProjectName());
        assertEquals("testCollection", entityEvent.getCollName());
        assertEquals(EntityRef.of(dbEntry), entityEvent.getEntity());
    }

    @Test
    public void test_equals_and_hashCode() {
        EventType type = EventType.CREATED;
        String projectName = "testProject";
        String collName = "testCollection";
        JsonObject jsonObject = new JsonObject();
        DbEntry dbEntry = DbEntry.fromJsonObject(projectName, collName, jsonObject);
        EntityEvent entityEvent1 = new EntityEvent(type, projectName, collName, dbEntry);
        EntityEvent entityEvent2 = new EntityEvent(type, projectName, collName, dbEntry);
        assertEquals(entityEvent1, entityEvent2);
        assertEquals(entityEvent1.hashCode(), entityEvent2.hashCode());
    }

    @Test
    public void test_equals_same_instance() {
        DbEntry dbEntry = DbEntry.fromJsonObject("db", "coll", new JsonObject());
        EntityEvent event = new EntityEvent(EventType.CREATED, "db", "coll", dbEntry);
        assertEquals(event, event);
    }

    @Test
    public void test_equals_null_returns_false() {
        DbEntry dbEntry = DbEntry.fromJsonObject("db", "coll", new JsonObject());
        EntityEvent event = new EntityEvent(EventType.CREATED, "db", "coll", dbEntry);
        assertNotEquals(null, event);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        DbEntry dbEntry = DbEntry.fromJsonObject("db", "coll", new JsonObject());
        EntityEvent event = new EntityEvent(EventType.CREATED, "db", "coll", dbEntry);
        assertDiffers(event, "notAnEvent");
    }

    @Test
    public void test_equals_different_collName_returns_false() {
        DbEntry dbEntry = DbEntry.fromJsonObject("db", "coll", new JsonObject());
        EntityEvent event1 = new EntityEvent(EventType.CREATED, "db", "coll1", dbEntry);
        EntityEvent event2 = new EntityEvent(EventType.CREATED, "db", "coll2", dbEntry);
        assertNotEquals(event1, event2);
    }

    @Test
    public void test_hashCode_different_value_differs() {
        DbEntry dbEntry1 = DbEntry.fromJsonObject("db", "coll1", new JsonObject());
        DbEntry dbEntry2 = DbEntry.fromJsonObject("db", "coll2", new JsonObject());
        EntityEvent event1 = new EntityEvent(EventType.CREATED, "db", "coll1", dbEntry1);
        EntityEvent event2 = new EntityEvent(EventType.CREATED, "db", "coll2", dbEntry2);
        assertNotEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    public void test_toString_not_null() {
        DbEntry dbEntry = DbEntry.fromJsonObject("db", "coll", new JsonObject());
        EntityEvent event = new EntityEvent(EventType.CREATED, "db", "coll", dbEntry);
        assertNotNull(event.toString());
    }
}
