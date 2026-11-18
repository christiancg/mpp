package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.StopListenRequest;
import org.junit.jupiter.api.Test;

public class StopListenRequestTest {

    @Test
    public void constructor_setsStopListenType() {
        final var req = new StopListenRequest();

        assertEquals(OperationType.STOP_LISTEN, req.getType());
    }

    @Test
    public void listenId_getterSetterRoundTrip() {
        final var req = new StopListenRequest();
        final var id = "550e8400-e29b-41d4-a716-446655440000";

        req.setListenId(id);

        assertEquals(id, req.getListenId());
    }

    @Test
    public void listenId_defaultIsNull() {
        assertNull(new StopListenRequest().getListenId());
    }
}
