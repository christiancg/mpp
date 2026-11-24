package io.github.christiancg.mpp.client.scripting;

import io.github.christiancg.mpp.client.internal.ClientContext;
import io.github.christiancg.mpp.client.internal.Descriptors;
import io.github.christiancg.mpp.client.internal.ResponseDecoder;
import io.github.christiancg.mpp.client.protocol.Operation;
import java.util.List;

public final class Middlewares {
    private final ClientContext context;
    private final String project;

    public Middlewares(ClientContext context, String project) {
        this.context = context;
        this.project = project;
    }

    public MiddlewareSaved save(MiddlewareDefinition middleware) {
        final var reply = context.channel().send(Operation.SAVE_MIDDLEWARE,
                context.scripts().saveMiddleware(project, middleware));
        return new MiddlewareSaved(reply.path("version").asLong(0), reply.path("procedureVersion").asLong(0));
    }

    public void delete(String name) {
        context.channel().send(Operation.DELETE_MIDDLEWARE, context.scripts().deleteMiddleware(project, name));
    }

    public List<MiddlewareInfo> list() {
        final var reply = context.channel().send(Operation.LIST_MIDDLEWARES,
                context.scripts().listMiddlewares(project));
        return ResponseDecoder.documents(reply, "middlewares").stream().map(Descriptors::middleware).toList();
    }
}
