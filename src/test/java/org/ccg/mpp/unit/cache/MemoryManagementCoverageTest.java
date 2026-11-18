package org.ccg.mpp.unit.cache;

import static org.mockito.Mockito.mock;

import org.ccg.mpp.cache.MemoryManagement;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MemoryManagementCoverageTest {
    private final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private final Configuration configuration = Configuration.getInstance();
    private long realMaxMemory;
    private FileSystem realFs;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        realMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        realFs = TestUtils.getPrivateField(TestUtils.usageTrackerOf(memoryManagement), "fs", FileSystem.class);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", 100L * 1024 * 1024);
    }

    @AfterEach
    public void tearDown() throws Exception {
        memoryManagement.stopSweepThread();
        TestUtils.setPrivateField(TestUtils.usageTrackerOf(memoryManagement), "fs", realFs);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", realMaxMemory);
        TestUtils.standardTearDown();
    }

    @Test
    public void test_start_and_stop_sweep_thread() {
        memoryManagement.startSweepThread();
        memoryManagement.stopSweepThread();
    }

    @Test
    public void test_load_profile_swallows_stream_failure() throws Exception {
        final var throwingFs = mock(FileSystem.class, _ -> {
            throw new RuntimeException("boom");
        });
        TestUtils.setPrivateField(TestUtils.usageTrackerOf(memoryManagement), "fs", throwingFs);
        memoryManagement.loadProfileFromAdmin();
    }
}
