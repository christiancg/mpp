package org.ccg.mpp.unit.ejson;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ops.req.OperationRequest;
import org.junit.jupiter.api.Test;

public class ReflectionFailureLogTest {
    @Test
    public void test_an_unreadable_request_type_yields_no_object_however_often_it_is_sent() {
        final var ejson = new EJson();
        for (var i = 0; i < 100; i++) {
            assertNull(ejson.fromJson("{\"type\":\"BOGUS\"}", OperationRequest.class));
        }
    }
}
