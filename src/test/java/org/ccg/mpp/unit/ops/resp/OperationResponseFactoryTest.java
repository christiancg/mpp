package org.ccg.mpp.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.junit.jupiter.api.Test;

public class OperationResponseFactoryTest {
    private static final List<OperationType> COLLAPSED_TYPES = List.of(OperationType.AUTHENTICATE,
            OperationType.CHANGE_PERMISSIONS, OperationType.COMMIT_TRANSACTION, OperationType.CREATE_COLLECTION,
            OperationType.CREATE_PROJECT, OperationType.CREATE_INDEX, OperationType.CREATE_USER,
            OperationType.DELETE_PROCEDURE, OperationType.DELETE_SCHEDULE, OperationType.DELETE_SCHEMA,
            OperationType.DELETE_TRIGGER, OperationType.DELETE_USER, OperationType.DROP_COLLECTION,
            OperationType.DROP_PROJECT, OperationType.DROP_INDEX, OperationType.RESOLVE_TRANSACTION,
            OperationType.ROLLBACK_TRANSACTION, OperationType.SET_PROJECT_OWNERS, OperationType.SET_PASSWORD);

    @Test
    public void test_ok_carries_type_ok_status_and_message() {
        for (final var type : COLLAPSED_TYPES) {
            final var response = OperationResponse.ok(type, "done");
            assertEquals(type, response.getType());
            assertEquals(OperationStatus.OK, response.getStatus());
            assertEquals("done", response.getMessage());
            assertNull(response.getErrorCode());
        }
    }

    @Test
    public void test_ok_accepts_null_message() {
        final var response = OperationResponse.ok(OperationType.CREATE_PROJECT, null);
        assertEquals(OperationType.CREATE_PROJECT, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNull(response.getMessage());
    }
}
