package io.github.christiancg.mpp.client;

import io.github.christiancg.mpp.client.error.NotFoundException;
import io.github.christiancg.mpp.client.internal.ClientContext;
import io.github.christiancg.mpp.client.internal.ResponseDecoder;
import io.github.christiancg.mpp.client.protocol.Operation;
import java.util.List;
import java.util.Optional;

public final class Schema {
    private final ClientContext context;
    private final String database;
    private final String collection;

    Schema(ClientContext context, String database, String collection) {
        this.context = context;
        this.database = database;
        this.collection = collection;
    }

    public List<String> save(Document schema) {
        final var reply = context.channel().send(Operation.SAVE_SCHEMA,
                context.encoder().saveSchema(database, collection, schema));
        return ResponseDecoder.strings(reply.get("warnings"));
    }

    public Optional<Document> get() {
        try {
            final var reply = context.channel().send(Operation.GET_SCHEMA,
                    context.encoder().getSchema(database, collection));
            return ResponseDecoder.document(reply, "schema");
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    public void delete() {
        context.channel().send(Operation.DELETE_SCHEMA, context.encoder().deleteSchema(database, collection));
    }
}
