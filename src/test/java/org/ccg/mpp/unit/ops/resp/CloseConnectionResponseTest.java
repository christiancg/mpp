package org.ccg.mpp.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.resp.CloseConnectionResponse;
import org.junit.jupiter.api.Test;

public class CloseConnectionResponseTest {
    @Test
    public void test_constructor_creates_instance_with_close_connection_type() {
        CloseConnectionResponse response = new CloseConnectionResponse();

        assertEquals(OperationType.CLOSE_CONNECTION, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(Globals.CLOSE_CONNECTION_MESSAGE, response.getMessage());
    }
}
