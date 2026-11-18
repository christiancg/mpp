package org.ccg.mpp.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.*;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.resp.StopListenResponse;
import org.junit.jupiter.api.Test;

public class StopListenResponseTest {

    @Test
    public void constructor_setsTypeAndStatus() {
        final var resp = new StopListenResponse();

        assertEquals(OperationType.STOP_LISTEN, resp.getType());
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertNotNull(resp.getMessage());
    }
}
