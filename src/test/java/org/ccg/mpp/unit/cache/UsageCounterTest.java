package org.ccg.mpp.unit.cache;

import static org.junit.jupiter.api.Assertions.*;

import org.ccg.mpp.cache.AccessKind;
import org.ccg.mpp.cache.UsageCounter;
import org.junit.jupiter.api.Test;

public class UsageCounterTest {

    @Test
    public void test_constructor_records_initial_state() {
        final var c = new UsageCounter(AccessKind.COLLECTION, "db", "coll", "field", 5L, 100L);
        assertEquals(AccessKind.COLLECTION, c.kind());
        assertEquals("db", c.projectName());
        assertEquals("coll", c.collName());
        assertEquals("field", c.indexKey());
        assertEquals(5L, c.getAccessCount());
        assertEquals(100L, c.getLastAccessMillis());
    }

    @Test
    public void test_null_indexKey_normalized_to_empty() {
        final var c = new UsageCounter(AccessKind.PK_INDEX, "db", "coll", null, 0L, 0L);
        assertEquals("", c.indexKey());
    }

    @Test
    public void test_increment_updates_count_and_timestamp() {
        final var c = new UsageCounter(AccessKind.COLLECTION, "db", "coll", "", 0L, 0L);
        c.increment(500L);
        c.increment(600L);
        assertEquals(2L, c.getAccessCount());
        assertEquals(600L, c.getLastAccessMillis());
    }
}
