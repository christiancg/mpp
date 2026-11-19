package org.ccg.mpp.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.simplejs.exceptions.JsThrowException;
import org.ccg.mpp.simplejs.host.EnforcingProjectAccess;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EnforcingProjectAccessUserDeletedTest {
    private static final String USER = "vanishinguser";

    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        TestUtils.createAdmin(USER);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("value", new JsonString("v"));
        return object;
    }

    private EnforcingProjectAccess transactionHoldingTheCollectionLock() throws Exception {
        final var project = new EnforcingProjectAccess(USER, null);
        project.beginTransaction();
        project.save(TestGlobals.PROJECT, TestGlobals.COLL, document("first"));
        AdminOperationHelper.deleteUserEntry(USER);
        return project;
    }

    private boolean collectionWriteLockIsFreeForAnotherThread() throws Exception {
        final var acquired = new boolean[1];
        final var probe = new Thread(() -> {
            try {
                acquired[0] = locks.tryLockWrite(TestGlobals.PROJECT, TestGlobals.COLL, 200);
                if (acquired[0]) {
                    locks.releaseWrite(TestGlobals.PROJECT, TestGlobals.COLL);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        probe.start();
        probe.join(TimeUnit.SECONDS.toMillis(5));
        return acquired[0];
    }

    @Test
    public void test_a_save_after_the_user_vanished_is_still_refused() throws Exception {
        final var project = transactionHoldingTheCollectionLock();

        assertThrows(JsThrowException.class,
                () -> project.save(TestGlobals.PROJECT, TestGlobals.COLL, document("second")));
    }

    @Test
    public void test_a_rollback_after_the_user_vanished_releases_the_write_lock() throws Exception {
        final var project = transactionHoldingTheCollectionLock();

        assertDoesNotThrow(project::rollbackTransaction);

        assertFalse(project.hasActiveTransaction());
        assertTrue(collectionWriteLockIsFreeForAnotherThread());
    }

    @Test
    public void test_a_commit_after_the_user_vanished_is_refused_and_leaves_no_lock_behind() throws Exception {
        final var project = transactionHoldingTheCollectionLock();

        assertThrows(JsThrowException.class, project::commitTransaction);

        assertTrue(collectionWriteLockIsFreeForAnotherThread());
    }
}
