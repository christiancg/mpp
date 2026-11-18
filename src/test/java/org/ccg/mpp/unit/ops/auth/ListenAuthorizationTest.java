package org.ccg.mpp.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.ops.auth.AuthorizationChecker;
import org.ccg.mpp.ops.req.ListenRequest;
import org.ccg.mpp.ops.req.StopListenRequest;
import org.junit.jupiter.api.Test;

public class ListenAuthorizationTest {

    private AdminUserEntry adminUser() {
        return new AdminUserEntry("admin", "hash", true, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    private AdminUserEntry noPermsUser() {
        return new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    private AdminUserEntry readUser() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("db" + "|" + "coll", PermissionLevel.READ);
        return new AdminUserEntry("reader", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
    }

    @Test
    public void listen_admin_isAllowed() {
        final var req = listenRequest();

        assertTrue(AuthorizationChecker.check(req, adminUser()).isAllowed());
    }

    @Test
    public void listen_noPermissions_isDenied() {
        final var req = listenRequest();

        assertFalse(AuthorizationChecker.check(req, noPermsUser()).isAllowed());
    }

    @Test
    public void listen_withReadPermission_isAllowed() {
        final var req = listenRequest();

        assertTrue(AuthorizationChecker.check(req, readUser()).isAllowed());
    }

    @Test
    public void stopListen_noPermissions_isAllowed() {
        final var req = new StopListenRequest();
        req.setListenId(UUID.randomUUID().toString());

        assertTrue(AuthorizationChecker.check(req, noPermsUser()).isAllowed());
    }

    @Test
    public void stopListen_admin_isAllowed() {
        final var req = new StopListenRequest();
        req.setListenId(UUID.randomUUID().toString());

        assertTrue(AuthorizationChecker.check(req, adminUser()).isAllowed());
    }

    private static ListenRequest listenRequest() {
        final var req = new ListenRequest("db", "coll");
        req.setAggregationSteps(List.of());
        return req;
    }
}
