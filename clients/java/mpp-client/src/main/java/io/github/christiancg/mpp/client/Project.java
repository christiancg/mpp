package io.github.christiancg.mpp.client;

import io.github.christiancg.mpp.client.internal.ClientContext;
import io.github.christiancg.mpp.client.internal.CollectionOps;
import io.github.christiancg.mpp.client.internal.Descriptors;
import io.github.christiancg.mpp.client.internal.Names;
import io.github.christiancg.mpp.client.internal.ResponseDecoder;
import io.github.christiancg.mpp.client.internal.ScriptResults;
import io.github.christiancg.mpp.client.mapping.JacksonCodec;
import io.github.christiancg.mpp.client.protocol.Operation;
import io.github.christiancg.mpp.client.scripting.Entrypoints;
import io.github.christiancg.mpp.client.scripting.Middlewares;
import io.github.christiancg.mpp.client.scripting.Procedures;
import io.github.christiancg.mpp.client.scripting.Schedules;
import io.github.christiancg.mpp.client.scripting.TriggerInfo;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

public final class Project {
    private final ClientContext context;
    private final String name;

    Project(ClientContext context, String name) {
        this.context = context;
        this.name = Names.project(name);
    }

    public String name() {
        return name;
    }

    public void create() {
        context.channel().send(Operation.CREATE_PROJECT, context.encoder().createProject(name));
    }

    public void drop() {
        context.channel().send(Operation.DROP_PROJECT, context.encoder().dropProject(name));
    }

    public List<String> listCollections() {
        final var reply = context.channel().send(Operation.LIST_COLLECTIONS, context.encoder().listCollections(name));
        return ResponseDecoder.strings(reply.get("collections"));
    }

    public Collection<Document> collection(String collection) {
        return collection(collection, DocumentCodec.identity());
    }

    public <T> Collection<T> collection(String collection, Class<T> type) {
        return collection(collection, new JacksonCodec<>(type, context.json()));
    }

    public <T> Collection<T> collection(String collection, DocumentCodec<T> codec) {
        return new Collection<>(new CollectionOps<>(context, context.channel(), name, collection, codec));
    }

    public Document stats() {
        final var reply = context.channel().send(Operation.GET_PROJECT_STATS, context.encoder().projectStats(name));
        return ResponseDecoder.document(reply, "stats").orElseGet(Document::empty);
    }

    public void setOwners(List<String> owners) {
        context.channel().send(Operation.SET_PROJECT_OWNERS, context.encoder().setProjectOwners(name, owners));
    }

    public ScriptResult runScript(String script) {
        return run(context.scripts().runScript(name, script));
    }

    public ScriptResult runScript(String script, Document args) {
        return run(context.scripts().runScript(name, script, args));
    }

    public Procedures procedures() {
        return new Procedures(context, name);
    }

    public Schedules schedules() {
        return new Schedules(context, name);
    }

    public Entrypoints entrypoints() {
        return new Entrypoints(context, name);
    }

    public Middlewares middlewares() {
        return new Middlewares(context, name);
    }

    public List<TriggerInfo> listTriggers() {
        final var reply = context.channel().send(Operation.LIST_TRIGGERS, context.scripts().listTriggers(name));
        return ResponseDecoder.documents(reply, "triggers").stream().map(Descriptors::trigger).toList();
    }

    private ScriptResult run(ObjectNode request) {
        return ScriptResults.of(context.json(), context.channel().send(Operation.RUN_SCRIPT, request));
    }
}
