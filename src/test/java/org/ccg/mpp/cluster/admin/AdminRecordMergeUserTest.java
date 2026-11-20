package org.ccg.mpp.cluster.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AdminRecordMergeUserTest extends AdminRecordTestBase {
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final List<UUID> clients = new ArrayList<>();

    @BeforeEach
    public void setUpClients() throws Exception {
        TestUtils.setPrivateField(config, "maxConnections", 0);
    }

    @AfterEach
    public void tearDownClients() throws Exception {
        clients.forEach(clientTracker::removeById);
        TestUtils.setPrivateField(config, "maxConnections", "100");
    }

    private UUID connectAs(String username) {
        final var clientId = clientTracker.addClient(TestUtils.clientSocket());
        clients.add(clientId);
        clientTracker.setAuthenticatedUser(clientId, username);
        return clientId;
    }

    private static AdminRecord userWithHash(String username, String hash, long version,
            Map<String, PermissionLevel> grants) {
        return new AdminRecord(AdminRecordKey.user(username), version,
                bodyOf(new AdminUserEntry(username, hash, false, Set.of(), grants, new HashMap<>())));
    }

    @Test
    public void install_with_changed_hash_deauthenticates_the_user() {
        AdminRecordMerge.apply(List.of(userWithHash("gina", "hash-one", clock.next(), new HashMap<>())), true);
        final var session = connectAs("gina");
        final var bystander = connectAs("hank");

        AdminRecordMerge.apply(List.of(userWithHash("gina", "hash-two", clock.next(), new HashMap<>())), true);

        assertEquals("hash-two", cache.getAdminUserEntry("gina").getPasswordHash());
        assertNull(clientTracker.getAuthenticatedUsername(session));
        assertEquals("hank", clientTracker.getAuthenticatedUsername(bystander));
    }

    @Test
    public void install_with_same_hash_and_new_permissions_does_not() {
        AdminRecordMerge.apply(List.of(userWithHash("gina", "hash-one", clock.next(), new HashMap<>())), true);
        final var session = connectAs("gina");

        AdminRecordMerge.apply(List
                .of(userWithHash("gina", "hash-one", clock.next(), Map.of(TestGlobals.PROJECT, PermissionLevel.READ))),
                true);

        assertEquals(PermissionLevel.READ,
                cache.getAdminUserEntry("gina").getProjectPermissions().get(TestGlobals.PROJECT));
        assertEquals("gina", clientTracker.getAuthenticatedUsername(session));
    }

    @Test
    public void first_install_deauthenticates_nobody() {
        final var session = connectAs("ivy");

        AdminRecordMerge.apply(List.of(userWithHash("ivy", "hash-first", clock.next(), new HashMap<>())), true);

        assertEquals("hash-first", cache.getAdminUserEntry("ivy").getPasswordHash());
        assertEquals("ivy", clientTracker.getAuthenticatedUsername(session));
    }
}
