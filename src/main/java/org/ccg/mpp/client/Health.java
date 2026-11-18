package org.ccg.mpp.client;

import java.io.PrintStream;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;

public final class Health {
    public static final String READY = "READY";
    private static final String SERVER = "server";
    private static final String STATE = "state";
    private static final EJson eJson = IocContainer.get(EJson.class);

    private Health() {
    }

    public static int check(Connection connection, PrintStream out) {
        final var raw = connection.sendRawLine(eJson.toJson(Requests.serverInfo()));
        out.println(raw);
        return READY.equals(stateOf(Connection.parseReply(raw))) ? ClientException.OK : ClientException.FAILED;
    }

    private static String stateOf(JsonObject reply) {
        final var server = reply.get(SERVER);
        return server != null && server.isJsonObject() ? Connection.stringOf(server.asJsonObject(), STATE) : null;
    }
}
