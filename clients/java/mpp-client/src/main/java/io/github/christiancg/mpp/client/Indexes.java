package io.github.christiancg.mpp.client;

import io.github.christiancg.mpp.client.internal.ClientContext;
import io.github.christiancg.mpp.client.internal.ResponseDecoder;
import io.github.christiancg.mpp.client.protocol.Operation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import tools.jackson.databind.node.ObjectNode;

public final class Indexes {
    private final ClientContext context;
    private final String project;
    private final String collection;

    Indexes(ClientContext context, String project, String collection) {
        this.context = context;
        this.project = project;
        this.collection = collection;
    }

    public void create(String field, String... moreFields) {
        final var fields = new ArrayList<String>();
        fields.add(Objects.requireNonNull(field, "field"));
        fields.addAll(Arrays.asList(moreFields));
        context.channel().send(Operation.CREATE_INDEX, context.encoder().createIndex(project, collection, fields));
    }

    public void drop(String field) {
        context.channel().send(Operation.DROP_INDEX, context.encoder().dropIndex(project, collection, field));
    }

    public ReindexResult reindex(String... fields) {
        return result(context.channel().send(Operation.REINDEX,
                context.encoder().reindex(project, collection, List.of(fields))));
    }

    public ReindexResult reindexPrimaryKey() {
        return result(
                context.channel().send(Operation.REINDEX, context.encoder().reindexPrimaryKey(project, collection)));
    }

    private static ReindexResult result(ObjectNode reply) {
        return new ReindexResult(ResponseDecoder.strings(reply.get("rebuiltFields")),
                optionalLong(reply, "primaryKeyEntries"), optionalLong(reply, "unversionedEntries"));
    }

    private static OptionalLong optionalLong(ObjectNode reply, String field) {
        final var node = reply.get(field);
        return node == null || !node.isNumber() ? OptionalLong.empty() : OptionalLong.of(node.longValue());
    }
}
