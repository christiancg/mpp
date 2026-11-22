package io.github.christiancg.mpp.client.scripting;

import io.github.christiancg.mpp.client.internal.ClientContext;
import io.github.christiancg.mpp.client.internal.Descriptors;
import io.github.christiancg.mpp.client.internal.ResponseDecoder;
import io.github.christiancg.mpp.client.protocol.Operation;
import java.util.List;

public final class Entrypoints {
    private final ClientContext context;
    private final String project;

    public Entrypoints(ClientContext context, String project) {
        this.context = context;
        this.project = project;
    }

    public EntrypointSaved save(EntrypointDefinition entrypoint) {
        final var reply = context.channel().send(Operation.SAVE_ENTRYPOINT,
                context.scripts().saveEntrypoint(project, entrypoint));
        return new EntrypointSaved(reply.path("version").asLong(0), reply.path("procedureVersion").asLong(0));
    }

    public void delete(String name) {
        context.channel().send(Operation.DELETE_ENTRYPOINT, context.scripts().deleteEntrypoint(project, name));
    }

    public List<EntrypointInfo> list() {
        final var reply = context.channel().send(Operation.LIST_ENTRYPOINTS,
                context.scripts().listEntrypoints(project));
        return ResponseDecoder.documents(reply, "entrypoints").stream().map(Descriptors::entrypoint).toList();
    }
}
