package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.SetProjectOwnersRequest;
import org.junit.jupiter.api.Test;

public class SetProjectOwnersRequestTest {
    @Test
    public void test_operation_type() {
        final var req = new SetProjectOwnersRequest("mydb");
        assertEquals(OperationType.SET_PROJECT_OWNERS, req.getType());
        assertEquals("mydb", req.getProjectName());
    }

    @Test
    public void test_get_owners_empty_by_default() {
        final var req = new SetProjectOwnersRequest("mydb");
        assertTrue(req.getOwners().isEmpty());
    }

    @Test
    public void test_set_and_get_owners() {
        final var req = new SetProjectOwnersRequest("mydb");
        req.setOwners(List.of("Alice", "bob"));
        assertEquals(List.of("Alice", "bob"), req.getOwners());
    }

    @Test
    public void test_parser_parses_set_project_owners() {
        final var msg = "{\"type\":\"SET_PROJECT_OWNERS\",\"projectName\":\"mydb\",\"owners\":[\"Alice\",\"bob\"]}";
        final var req = (SetProjectOwnersRequest) org.ccg.mpp.ops.req.RequestParser.parseRequest(msg);
        assertEquals(OperationType.SET_PROJECT_OWNERS, req.getType());
        assertEquals("mydb", req.getProjectName());
        assertEquals(List.of("Alice", "bob"), req.getOwners());
    }
}
