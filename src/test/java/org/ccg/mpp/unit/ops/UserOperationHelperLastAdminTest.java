package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.UserOperationHelper;
import org.ccg.mpp.ops.admin.RestampedRecords;
import org.ccg.mpp.ops.req.ChangePermissionsRequest;
import org.ccg.mpp.ops.req.CreateUserRequest;
import org.ccg.mpp.ops.req.DeleteUserRequest;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class UserOperationHelperLastAdminTest {

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        final var req = new CreateUserRequest();
        req.setUsername("sole_admin");
        req.setPassword("password123");
        req.setAdmin(true);
        req.setGlobalPermissions(new HashSet<>());
        req.setDatabasePermissions(new HashMap<>());
        req.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(req);
    }

    @AfterAll
    public static void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    @Test
    public void test_delete_last_admin_refused() {
        final var req = new DeleteUserRequest();
        req.setUsername("sole_admin");
        final var resp = UserOperationHelper.processDeleteUser(req, new RestampedRecords());
        assertEquals(OperationStatus.ERROR, resp.getStatus());
        assertTrue(resp.getMessage().contains("last admin"));
    }

    @Test
    public void test_demote_last_admin_refused() {
        final var req = new ChangePermissionsRequest();
        req.setUsername("sole_admin");
        req.setAdmin(false);
        req.setGlobalPermissions(new HashSet<>());
        req.setDatabasePermissions(new HashMap<>());
        req.setCollectionPermissions(new HashMap<>());
        final var resp = UserOperationHelper.processChangePermissions(req, new RestampedRecords());
        assertEquals(OperationStatus.ERROR, resp.getStatus());
        assertTrue(resp.getMessage().contains("last admin"));
    }
}
