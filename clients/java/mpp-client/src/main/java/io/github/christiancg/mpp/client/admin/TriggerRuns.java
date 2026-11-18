package io.github.christiancg.mpp.client.admin;

import io.github.christiancg.mpp.client.internal.ClientContext;
import io.github.christiancg.mpp.client.internal.Descriptors;
import io.github.christiancg.mpp.client.internal.ResponseDecoder;
import io.github.christiancg.mpp.client.protocol.Operation;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

public final class TriggerRuns {
    private final ClientContext context;

    TriggerRuns(ClientContext context) {
        this.context = context;
    }

    public List<TriggerRun> list() {
        return runs(context.scripts().listTriggerRuns());
    }

    public List<TriggerRun> list(TriggerRunStatus status) {
        return runs(context.scripts().listTriggerRuns(status));
    }

    public boolean resolve(String runId, TriggerRunDecision decision) {
        return context.channel()
                .send(Operation.RESOLVE_TRIGGER_RUN, context.scripts().resolveTriggerRun(runId, decision))
                .path("resolved").asBoolean(false);
    }

    private List<TriggerRun> runs(ObjectNode request) {
        final var reply = context.channel().send(Operation.LIST_TRIGGER_RUNS, request);
        return ResponseDecoder.documents(reply, "runs").stream().map(Descriptors::triggerRun).toList();
    }
}
