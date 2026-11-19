package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.RequestParser;
import org.ccg.mpp.ops.req.ServerInfoRequest;
import org.ccg.mpp.ops.req.validations.RequestValidator;
import org.junit.jupiter.api.Test;

public class ServerInfoRequestTest {
    @Test
    public void test_parses_from_the_type_alone() {
        final var request = RequestParser.parseRequest("{\"type\":\"SERVER_INFO\"}");

        final var info = assertInstanceOf(ServerInfoRequest.class, request);
        assertEquals(OperationType.SERVER_INFO, info.getType());
        assertNull(info.getProjectName());
        assertNull(info.getCollectionName());
        assertFalse(info.isShuttingDown());
        assertTrue(RequestValidator.validate(info).isValid());
    }

    @Test
    public void test_the_shutting_down_mark_is_never_serialised() {
        final var request = new ServerInfoRequest();
        request.markShuttingDown(true);

        assertTrue(request.isShuttingDown());
        assertFalse(IocContainer.get(EJson.class).toJson(request).contains("shuttingDown"));
    }
}
