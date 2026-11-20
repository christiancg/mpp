package org.ccg.mpp.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.auth.AuthorizationChecker;
import org.ccg.mpp.ops.req.RequestParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class AuthorizationCheckerScopeInjectionTest {
    private static final String PROJECT = "scopeProject";
    private static final String GRANTED = "grantedColl";

    private static AdminUserEntry user(Map<String, PermissionLevel> projectGrants,
            Map<String, PermissionLevel> collectionGrants) {
        return new AdminUserEntry("scope_user", "h", false, Set.of(), new HashMap<>(projectGrants),
                new HashMap<>(collectionGrants));
    }

    private static AdminUserEntry collectionOnlyReader() {
        return user(Map.of(), Map.of(PROJECT + "|" + GRANTED, PermissionLevel.READ));
    }

    private static boolean allowed(String requestJson, AdminUserEntry user) {
        return AuthorizationChecker.check(RequestParser.parseRequest(requestJson), user).isAllowed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIST_PROCEDURES", "LIST_SCHEDULES", "LIST_COLLECTIONS"})
    public void test_a_project_wide_list_ignores_an_injected_collection_name(String type) {
        final var injected = "{\"type\":\"" + type + "\",\"projectName\":\"" + PROJECT + "\",\"collectionName\":\""
                + GRANTED + "\",\"includeSource\":true}";
        assertFalse(allowed(injected, collectionOnlyReader()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIST_PROCEDURES", "LIST_SCHEDULES", "LIST_COLLECTIONS"})
    public void test_a_project_wide_list_is_allowed_with_a_project_grant(String type) {
        final var request = "{\"type\":\"" + type + "\",\"projectName\":\"" + PROJECT + "\"}";
        assertTrue(allowed(request, user(Map.of(PROJECT, PermissionLevel.READ), Map.of())));
    }

    @Test
    public void test_list_triggers_still_honours_its_optional_collection() {
        final var request = "{\"type\":\"LIST_TRIGGERS\",\"projectName\":\"" + PROJECT + "\",\"collectionName\":\""
                + GRANTED + "\"}";
        assertTrue(allowed(request, collectionOnlyReader()));
        assertFalse(
                allowed("{\"type\":\"LIST_TRIGGERS\",\"projectName\":\"" + PROJECT + "\"}", collectionOnlyReader()));
    }

    @Test
    public void test_a_collection_scoped_read_still_uses_the_collection_grant() {
        final var request = "{\"type\":\"FIND_BY_ID\",\"projectName\":\"" + PROJECT + "\",\"collectionName\":\""
                + GRANTED + "\",\"_id\":\"a\"}";
        assertTrue(allowed(request, collectionOnlyReader()));
    }

    @Test
    public void test_every_operation_type_declares_a_scope() {
        for (final var type : OperationType.values()) {
            assertNotNull(type.scope(), type.name());
        }
        assertEquals(OperationType.Scope.PROJECT, OperationType.LIST_PROCEDURES.scope());
        assertEquals(OperationType.Scope.OPTIONAL_COLLECTION, OperationType.LIST_TRIGGERS.scope());
        assertEquals(OperationType.Scope.COLLECTION, OperationType.SAVE.scope());
        assertEquals(OperationType.Scope.NONE, OperationType.AUTHENTICATE.scope());
    }

    @Test
    public void test_may_write_follows_admin_and_collection_grants() {
        assertFalse(AuthorizationChecker.mayWrite(null, PROJECT, GRANTED));
        assertTrue(AuthorizationChecker.mayWrite(
                new AdminUserEntry("root", "h", true, Set.of(), new HashMap<>(), new HashMap<>()), PROJECT, GRANTED));
        assertTrue(AuthorizationChecker.mayWrite(
                user(Map.of(), Map.of(PROJECT + "|" + GRANTED, PermissionLevel.READ_WRITE)), PROJECT, GRANTED));
        assertFalse(AuthorizationChecker.mayWrite(collectionOnlyReader(), PROJECT, GRANTED));
        assertFalse(AuthorizationChecker.mayWrite(user(Map.of(PROJECT, PermissionLevel.READ_WRITE),
                Map.of(PROJECT + "|" + GRANTED, PermissionLevel.READ)), PROJECT, GRANTED));
        assertTrue(AuthorizationChecker.mayWrite(user(Map.of(PROJECT, PermissionLevel.READ_WRITE), Map.of()), PROJECT,
                "other"));
    }
}
