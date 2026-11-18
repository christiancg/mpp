package org.ccg.mpp.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.resp.SaveSchemaResponse;
import org.junit.jupiter.api.Test;

public class SaveSchemaResponseTest {
    @Test
    public void test_response_with_warnings() {
        final var response = new SaveSchemaResponse("saved", List.of("w1"));
        assertEquals(OperationType.SAVE_SCHEMA, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("saved", response.getMessage());
        assertEquals(List.of("w1"), response.getWarnings());
    }

    @Test
    public void test_response_without_warnings() {
        final var response = new SaveSchemaResponse("saved", List.of());
        assertTrue(response.getWarnings().isEmpty());
    }
}
