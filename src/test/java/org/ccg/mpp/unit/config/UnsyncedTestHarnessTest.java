package org.ccg.mpp.unit.config;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.ccg.mpp.config.Configuration;
import org.junit.jupiter.api.Test;

public class UnsyncedTestHarnessTest {

    @Test
    public void test_unit_tests_run_with_unsynced_writes() {
        assertFalse(Configuration.getInstance().isSyncWrites(),
                "UnsyncedWritesExtension must turn syncWrites off for every test; a test that checks forcing turns"
                        + " it on itself with RecordingForcer");
    }
}
