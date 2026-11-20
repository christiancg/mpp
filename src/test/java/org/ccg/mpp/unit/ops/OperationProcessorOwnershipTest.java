package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.UserOperationHelper;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.CreateUserRequest;
import org.ccg.mpp.ops.req.SetProjectOwnersRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class OperationProcessorOwnershipTest {
    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private static final Cache cache = IocContainer.get(Cache.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    @Test
    public void test_create_project_sets_authenticated_user_as_owner() throws Exception {
        final var createReq = new CreateUserRequest();
        createReq.setUsername("project_creator");
        createReq.setPassword("password123");
        createReq.setAdmin(false);
        createReq.setGlobalPermissions(new java.util.HashSet<>());
        createReq.setProjectPermissions(new java.util.HashMap<>());
        createReq.setCollectionPermissions(new java.util.HashMap<>());
        UserOperationHelper.processCreateUser(createReq);

        final var clientTracker = IocContainer.get(org.ccg.mpp.conn.ClientTracker.class);
        final var fakeSocket = org.mockito.Mockito.mock(java.net.Socket.class);
        final var fakeAddr = org.mockito.Mockito.mock(java.net.InetAddress.class);
        org.mockito.Mockito.when(fakeSocket.getInetAddress()).thenReturn(fakeAddr);
        org.mockito.Mockito.when(fakeAddr.getHostAddress()).thenReturn("127.0.0.1");
        TestUtils.setPrivateField(org.ccg.mpp.config.Configuration.getInstance(), "maxConnections", 100);
        final var clientId = clientTracker.addClient(fakeSocket);
        clientTracker.setAuthenticatedUser(clientId, "project_creator");

        final var req = new CreateProjectRequest("owner_test-db");
        final var resp = processor.processMessage(req, clientId);
        assertEquals(OperationStatus.OK, resp.getStatus());

        final var dbEntry = cache.getAdminProjectEntry("owner_test-db");
        assertNotNull(dbEntry);
        assertTrue(dbEntry.isOwner("project_creator"), "Creator should be set as owner");

        clientTracker.removeById(clientId);
    }

    @Test
    public void test_set_project_owners_success() throws Exception {
        TestUtils.createTestProjectAndCollection();
        final var alice = new CreateUserRequest();
        alice.setUsername("Alice");
        alice.setPassword("password123");
        alice.setAdmin(false);
        alice.setGlobalPermissions(new java.util.HashSet<>());
        alice.setProjectPermissions(new java.util.HashMap<>());
        alice.setCollectionPermissions(new java.util.HashMap<>());
        UserOperationHelper.processCreateUser(alice);

        final var req = new SetProjectOwnersRequest(TestGlobals.PROJECT);
        req.setOwners(List.of("Alice"));

        final var resp = processor.processMessage(req, null);
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertTrue(cache.getAdminProjectEntry(TestGlobals.PROJECT).isOwner("Alice"));
    }

    @Test
    public void test_set_project_owners_not_found() {
        final var req = new SetProjectOwnersRequest("nonexistent_project");
        final var resp = processor.processMessage(req, null);
        assertEquals(OperationStatus.NOT_FOUND, resp.getStatus());
    }
}
