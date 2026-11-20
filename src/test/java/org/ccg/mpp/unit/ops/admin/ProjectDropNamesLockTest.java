package org.ccg.mpp.unit.ops.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.admin.ProjectOperationHelper;
import org.ccg.mpp.ops.req.DropProjectRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class ProjectDropNamesLockTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(ProjectOperationHelper.class, "locks", locks);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void theNamesLockIsRemovedBeforeTheBarrierIsReleased() throws Exception {
        final var spy = Mockito.spy(locks);
        TestUtils.setPrivateStaticField(ProjectOperationHelper.class, "locks", spy);

        final var dropped = processor.processMessage(new DropProjectRequest(TestGlobals.PROJECT));

        assertEquals(OperationStatus.OK, dropped.getStatus(), dropped.getMessage());
        final var order = Mockito.inOrder(spy);
        order.verify(spy).removeLock(TestGlobals.PROJECT, Globals.COLLECTION_NAMES_LOCK);
        order.verify(spy).releaseProjectExclusive(TestGlobals.PROJECT);
        order.verify(spy).removeProjectLock(TestGlobals.PROJECT);
    }
}
