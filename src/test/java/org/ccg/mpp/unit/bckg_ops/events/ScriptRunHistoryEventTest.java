package org.ccg.mpp.unit.bckg_ops.events;

import static org.ccg.mpp.test.EqualityChecks.assertDiffers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.bckg_ops.events.ScriptRunHistoryEvent;
import org.ccg.mpp.ops.ScriptRunKind;
import org.ccg.mpp.ops.ScriptRunRecord;
import org.junit.jupiter.api.Test;

public class ScriptRunHistoryEventTest {
    private static ScriptRunRecord record(String runId) {
        return new ScriptRunRecord(runId, ScriptRunKind.TRIGGER, "shop", "audit", "proc", "orders", "CREATED",
                "definer", "alice", 1L, 2L, 1, ScriptRunRecord.OUTCOME_OK, null, null, null, null, null, false);
    }

    @Test
    public void test_carries_its_record_and_a_created_type() {
        final var runRecord = record("r1");
        final var event = new ScriptRunHistoryEvent(runRecord);
        assertSame(runRecord, event.getRecord());
        assertEquals(EventType.CREATED, event.getType());
    }

    @Test
    public void test_equality_follows_the_record() {
        assertEquals(new ScriptRunHistoryEvent(record("r1")), new ScriptRunHistoryEvent(record("r1")));
        assertEquals(new ScriptRunHistoryEvent(record("r1")).hashCode(),
                new ScriptRunHistoryEvent(record("r1")).hashCode());
        assertNotEquals(new ScriptRunHistoryEvent(record("r1")), new ScriptRunHistoryEvent(record("r2")));
        assertDiffers(new ScriptRunHistoryEvent(record("r1")), "not an event");
        final var event = new ScriptRunHistoryEvent(record("r1"));
        assertEquals(event, event);
    }

    @Test
    public void test_a_retry_carries_its_count_and_is_a_different_event() {
        final var retry = new ScriptRunHistoryEvent(record("r1"), 2);
        assertEquals(2, retry.getRetries());
        assertEquals(0, new ScriptRunHistoryEvent(record("r1")).getRetries());
        assertNotEquals(new ScriptRunHistoryEvent(record("r1")), retry);
        assertTrue(retry.toString().contains("retries=2"));
    }

    @Test
    public void test_to_string_names_the_run() {
        assertTrue(new ScriptRunHistoryEvent(record("r1")).toString().contains("r1"));
        assertTrue(new ScriptRunHistoryEvent(null).toString().contains("null"));
    }
}
