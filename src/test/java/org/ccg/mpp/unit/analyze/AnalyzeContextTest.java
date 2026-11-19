package org.ccg.mpp.unit.analyze;

import static org.junit.jupiter.api.Assertions.*;

import org.ccg.mpp.analyze.AnalyzeContext;
import org.junit.jupiter.api.Test;

public class AnalyzeContextTest {

    @Test
    public void test_counters_accumulate() {
        final var context = new AnalyzeContext();
        context.addScanned(3);
        context.addScanned(2);
        context.addIndexUsed("status");
        context.addIndexUsed("status");
        context.addIndexUsed("name");
        context.addLock("myProject|myColl");
        context.addLock("myProject|myColl|status");

        assertEquals(5, context.getDocumentsScanned());
        assertEquals(2, context.getIndexesUsed().size());
        assertTrue(context.getIndexesUsed().contains("status"));
        assertTrue(context.getIndexesUsed().contains("name"));
        assertEquals(2, context.getLocksAcquired().size());
        assertTrue(context.getLocksAcquired().contains("myProject|myColl|status"));
    }

    @Test
    public void test_field_lock_id_format() {
        assertEquals("myProject|myColl|status", AnalyzeContext.fieldLockId("myProject", "myColl", "status"));
    }
}
