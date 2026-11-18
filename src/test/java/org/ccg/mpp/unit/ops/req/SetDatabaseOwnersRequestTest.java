package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.SetDatabaseOwnersRequest;
import org.junit.jupiter.api.Test;

public class SetDatabaseOwnersRequestTest {
    @Test
    public void test_operation_type() {
        final var req = new SetDatabaseOwnersRequest("mydb");
        assertEquals(OperationType.SET_DATABASE_OWNERS, req.getType());
        assertEquals("mydb", req.getDatabaseName());
    }

    @Test
    public void test_get_owners_empty_by_default() {
        final var req = new SetDatabaseOwnersRequest("mydb");
        assertTrue(req.getOwners().isEmpty());
    }

    @Test
    public void test_set_and_get_owners() {
        final var req = new SetDatabaseOwnersRequest("mydb");
        req.setOwners(List.of("Alice", "bob"));
        assertEquals(List.of("Alice", "bob"), req.getOwners());
    }

    @Test
    public void test_parser_parses_set_database_owners() {
        final var msg = "{\"type\":\"SET_DATABASE_OWNERS\",\"databaseName\":\"mydb\",\"owners\":[\"Alice\",\"bob\"]}";
        final var req = (SetDatabaseOwnersRequest) org.ccg.mpp.ops.req.RequestParser.parseRequest(msg);
        assertEquals(OperationType.SET_DATABASE_OWNERS, req.getType());
        assertEquals("mydb", req.getDatabaseName());
        assertEquals(List.of("Alice", "bob"), req.getOwners());
    }
}
