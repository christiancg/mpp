package org.ccg.mpp.unit.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminPageEntry;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.DropProjectRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.ccg.mpp.utils.ReflectionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DropLockEvictionTest {
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private Map<String, ReentrantReadWriteLock> lockMap() throws NoSuchFieldException, IllegalAccessException {
        return TestUtils.getPrivateField(locks, "locks", new ReflectionUtils.TypeToken<>() {
        });
    }

    private void touch(String projectName, String collName) throws InterruptedException {
        locks.lock(projectName, collName);
        locks.release(projectName, collName);
    }

    @Test
    public void test_drop_evicts_admin_pages_and_collection_names_locks() throws Exception {
        final var pagesKey = Cache.getCollectionIdentifier(Globals.ADMIN_PAGES_PROJECT_NAME,
                AdminPageEntry.pagesCollectionName(TestGlobals.PROJECT, TestGlobals.COLL));
        final var namesKey = Cache.getCollectionIdentifier(TestGlobals.PROJECT, Globals.COLLECTION_NAMES_LOCK);
        touch(Globals.ADMIN_PAGES_PROJECT_NAME,
                AdminPageEntry.pagesCollectionName(TestGlobals.PROJECT, TestGlobals.COLL));
        touch(TestGlobals.PROJECT, Globals.COLLECTION_NAMES_LOCK);
        assertTrue(lockMap().containsKey(pagesKey));

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.PROJECT, TestGlobals.COLL)).getStatus());

        assertFalse(lockMap().containsKey(pagesKey), "the dropped collection's admin_pages lock is evicted");
        assertFalse(lockMap().containsKey(Cache.getCollectionIdentifier(TestGlobals.PROJECT, TestGlobals.COLL)));
        assertTrue(lockMap().containsKey(namesKey), "the project still has collections to name");

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropProjectRequest(TestGlobals.PROJECT)).getStatus());

        assertFalse(lockMap().containsKey(namesKey), "the dropped project's collection-names lock is evicted");
        assertFalse(lockMap().containsKey(TestGlobals.PROJECT));
    }

    @Test
    public void test_drop_forgets_the_admin_pages_identifier() {
        final var pagesColl = AdminPageEntry.pagesCollectionName(TestGlobals.PROJECT, TestGlobals.COLL);
        final var remembered = Cache.canonicalNames(Globals.ADMIN_PAGES_PROJECT_NAME, pagesColl);

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.PROJECT, TestGlobals.COLL)).getStatus());

        assertNotSame(remembered, Cache.canonicalNames(Globals.ADMIN_PAGES_PROJECT_NAME, pagesColl),
                "removing the page-row lock looks the identifier up again, so the drop forgets it afterwards");
    }
}
