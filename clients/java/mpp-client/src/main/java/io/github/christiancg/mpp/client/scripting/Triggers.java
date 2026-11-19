package io.github.christiancg.mpp.client.scripting;

import io.github.christiancg.mpp.client.Document;
import io.github.christiancg.mpp.client.internal.ClientContext;
import io.github.christiancg.mpp.client.internal.Descriptors;
import io.github.christiancg.mpp.client.internal.ResponseDecoder;
import io.github.christiancg.mpp.client.protocol.Operation;
import java.util.List;

public final class Triggers {
    private final ClientContext context;
    private final String project;
    private final String collection;

    public Triggers(ClientContext context, String project, String collection) {
        this.context = context;
        this.project = project;
        this.collection = collection;
    }

    public TriggerSaved save(TriggerDefinition trigger) {
        final var reply = context.channel().send(Operation.SAVE_TRIGGER,
                context.scripts().saveTrigger(project, collection, trigger));
        return new TriggerSaved(reply.path("version").asLong(0), reply.path("definer").asString(""));
    }

    public void delete(String name) {
        context.channel().send(Operation.DELETE_TRIGGER, context.scripts().deleteTrigger(project, collection, name));
    }

    public List<TriggerInfo> list() {
        final var reply = context.channel().send(Operation.LIST_TRIGGERS,
                context.scripts().listTriggers(project, collection));
        return ResponseDecoder.documents(reply, "triggers").stream().map(Descriptors::trigger).toList();
    }

    public TriggerTestResult test(String name, TriggerEvent event, Document document) {
        final var reply = context.channel().send(Operation.TEST_TRIGGER,
                context.scripts().testTrigger(project, collection, name, event, document));
        return new TriggerTestResult(reply.path("decision").asString(""), ResponseDecoder.document(reply, "document"),
                ResponseDecoder.strings(reply.get("logs")), reply.path("logsTruncated").asBoolean(false));
    }
}
