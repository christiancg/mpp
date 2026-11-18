package org.ccg.mpp.unit.bckg_ops.events;

import static org.ccg.mpp.test.EqualityChecks.assertDiffers;
import static org.junit.jupiter.api.Assertions.*;

import org.ccg.mpp.bckg_ops.events.Event;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.junit.jupiter.api.Test;

// Event is abstract; a minimal concrete subclass keeps this uncoupled from any production event type.
public class EventTest {

    private static final class TestEvent extends Event {
        TestEvent(EventType type) {
            super(type);
        }
    }

    @Test
    public void test_getType_returns_correct_type() {
        TestEvent event = new TestEvent(EventType.CREATED);
        assertEquals(EventType.CREATED, event.getType());
    }

    @Test
    public void test_equals_null_returns_false() {
        TestEvent event = new TestEvent(EventType.CREATED);
        assertNotEquals(null, event);
    }

    @Test
    public void test_equals_different_class_returns_false() {
        TestEvent event = new TestEvent(EventType.CREATED);
        assertDiffers(event, "string");
    }

    @Test
    public void test_equals_same_type() {
        TestEvent event1 = new TestEvent(EventType.UPDATED);
        TestEvent event2 = new TestEvent(EventType.UPDATED);
        assertEquals(event1, event2);
    }

    @Test
    public void test_equals_different_event_type_returns_false() {
        TestEvent event1 = new TestEvent(EventType.CREATED);
        TestEvent event2 = new TestEvent(EventType.DELETED);
        assertNotEquals(event1, event2);
    }

    @Test
    public void test_hashCode_same_values_equal() {
        TestEvent event1 = new TestEvent(EventType.CREATED);
        TestEvent event2 = new TestEvent(EventType.CREATED);
        assertEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    public void test_hashCode_different_type_differs() {
        TestEvent event1 = new TestEvent(EventType.CREATED);
        TestEvent event2 = new TestEvent(EventType.DELETED);
        assertNotEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    public void test_toString_not_null() {
        TestEvent event = new TestEvent(EventType.CREATED);
        assertNotNull(event.toString());
    }
}
