package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import org.ccg.mpp.data.auth.GlobalPermissionType;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.AuthenticateRequest;
import org.ccg.mpp.ops.req.ChangePermissionsRequest;
import org.ccg.mpp.ops.req.CreateUserRequest;
import org.ccg.mpp.ops.req.DeleteUserRequest;
import org.ccg.mpp.ops.req.RequestParser;
import org.junit.jupiter.api.Test;

public class UserRequestParserTest {
    @Test
    public void test_parses_authenticate_request() {
        final var msg = "{\"type\":\"AUTHENTICATE\",\"username\":\"Alice\",\"password\":\"secret\"}";
        final var req = (AuthenticateRequest) RequestParser.parseRequest(msg);
        assertEquals(OperationType.AUTHENTICATE, req.getType());
        assertEquals("Alice", req.getUsername());
        assertEquals("secret", req.getPassword());
    }

    @Test
    public void test_parses_delete_user_request() {
        final var msg = "{\"type\":\"DELETE_USER\",\"username\":\"Alice\"}";
        final var req = (DeleteUserRequest) RequestParser.parseRequest(msg);
        assertEquals(OperationType.DELETE_USER, req.getType());
        assertEquals("Alice", req.getUsername());
    }

    @Test
    public void test_parses_create_user_with_permissions() {
        final var msg = "{" + "\"type\":\"CREATE_USER\"," + "\"username\":\"bob\"," + "\"password\":\"secret1234\","
                + "\"admin\":false," + "\"globalPermissions\":[\"CREATE_DATABASE\"],"
                + "\"databasePermissions\":{\"mydb\":\"READ_WRITE\"},"
                + "\"collectionPermissions\":{\"mydb|coll\":\"READ\"}" + "}";
        final var req = (CreateUserRequest) RequestParser.parseRequest(msg);
        assertEquals(OperationType.CREATE_USER, req.getType());
        assertEquals("bob", req.getUsername());
        assertEquals("secret1234", req.getPassword());
        assertFalse(req.getAdmin());
        assertTrue(req.getGlobalPermissions().contains(GlobalPermissionType.CREATE_DATABASE));
        assertEquals(PermissionLevel.READ_WRITE, req.getDatabasePermissions().get("mydb"));
        assertEquals(PermissionLevel.READ, req.getCollectionPermissions().get("mydb|coll"));
    }

    @Test
    public void test_parses_create_user_with_empty_permissions() {
        final var msg = "{" + "\"type\":\"CREATE_USER\"," + "\"username\":\"carol\"," + "\"password\":\"secret1234\","
                + "\"admin\":true," + "\"globalPermissions\":[]," + "\"databasePermissions\":{},"
                + "\"collectionPermissions\":{}" + "}";
        final var req = (CreateUserRequest) RequestParser.parseRequest(msg);
        assertEquals("carol", req.getUsername());
        assertTrue(req.getAdmin());
        assertTrue(req.getGlobalPermissions().isEmpty());
        assertTrue(req.getDatabasePermissions().isEmpty());
        assertTrue(req.getCollectionPermissions().isEmpty());
    }

    @Test
    public void test_parses_change_permissions_request() {
        final var msg = "{" + "\"type\":\"CHANGE_PERMISSIONS\"," + "\"username\":\"bob\"," + "\"admin\":true,"
                + "\"globalPermissions\":[\"DROP_DATABASE\"]," + "\"databasePermissions\":{\"orders_db\":\"READ\"},"
                + "\"collectionPermissions\":{\"orders_db|invoices\":\"READ_WRITE\"}" + "}";
        final var req = (ChangePermissionsRequest) RequestParser.parseRequest(msg);
        assertEquals(OperationType.CHANGE_PERMISSIONS, req.getType());
        assertEquals("bob", req.getUsername());
        assertTrue(req.getAdmin());
        assertTrue(req.getGlobalPermissions().contains(GlobalPermissionType.DROP_DATABASE));
        assertEquals(PermissionLevel.READ, req.getDatabasePermissions().get("orders_db"));
        assertEquals(PermissionLevel.READ_WRITE, req.getCollectionPermissions().get("orders_db|invoices"));
    }

    @Test
    public void test_parses_change_permissions_empty() {
        final var msg = "{" + "\"type\":\"CHANGE_PERMISSIONS\"," + "\"username\":\"bob\"," + "\"admin\":false,"
                + "\"globalPermissions\":[]," + "\"databasePermissions\":{}," + "\"collectionPermissions\":{}" + "}";
        final var req = (ChangePermissionsRequest) RequestParser.parseRequest(msg);
        assertEquals("bob", req.getUsername());
        assertFalse(req.getAdmin());
        assertTrue(req.getGlobalPermissions().isEmpty());
        assertTrue(req.getDatabasePermissions().isEmpty());
        assertTrue(req.getCollectionPermissions().isEmpty());
    }
}
