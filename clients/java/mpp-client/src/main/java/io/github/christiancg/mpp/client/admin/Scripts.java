package io.github.christiancg.mpp.client.admin;

import io.github.christiancg.mpp.client.internal.ClientContext;
import io.github.christiancg.mpp.client.internal.Descriptors;
import io.github.christiancg.mpp.client.internal.ResponseDecoder;
import io.github.christiancg.mpp.client.protocol.Operation;
import java.util.List;

public final class Scripts {
    private final ClientContext context;

    Scripts(ClientContext context) {
        this.context = context;
    }

    public List<ScriptRun> list() {
        final var reply = context.channel().send(Operation.LIST_SCRIPTS, context.scripts().listScripts());
        return ResponseDecoder.documents(reply, "scripts").stream().map(Descriptors::scriptRun).toList();
    }

    public boolean cancel(String runId) {
        return context.channel().send(Operation.CANCEL_SCRIPT, context.scripts().cancelScript(runId)).path("cancelled")
                .asBoolean(false);
    }
}
