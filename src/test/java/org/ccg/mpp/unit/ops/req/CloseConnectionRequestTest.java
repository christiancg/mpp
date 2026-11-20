package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.CloseConnectionRequest;
import org.junit.jupiter.api.Test;

public class CloseConnectionRequestTest {
    @Test
    public void test_constructor_sets_close_connection_type() {
        CloseConnectionRequest request = new CloseConnectionRequest();

        assertEquals(OperationType.CLOSE_CONNECTION, request.getType());
        assertNull(request.getProjectName());
        assertNull(request.getCollectionName());
    }
}
