package org.ccg.mpp.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.resp.ServerInfoResponse;
import org.junit.jupiter.api.Test;

public class ServerInfoResponseTest {
    private static final EJson eJson = IocContainer.get(EJson.class);

    private static JsonObject ready() {
        final var server = new JsonObject();
        server.addProperty("state", "READY");
        return server;
    }

    @Test
    public void test_serialises_the_server_object() {
        final var response = new ServerInfoResponse("Ok", ready());

        final var frame = eJson.fromJson(eJson.toJson(response), JsonObject.class);

        assertEquals(OperationType.SERVER_INFO, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("SERVER_INFO", frame.get("type").asJsonString().getValue());
        assertEquals("READY", frame.get("server").asJsonObject().get("state").asJsonString().getValue());
    }

    @Test
    public void test_the_estimate_covers_the_payload() {
        final var response = new ServerInfoResponse("Ok", ready());

        assertTrue(response.estimatedJsonChars() >= eJson.toJson(response.getServer()).length());
    }
}
