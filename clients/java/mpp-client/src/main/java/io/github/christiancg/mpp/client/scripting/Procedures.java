package io.github.christiancg.mpp.client.scripting;

import io.github.christiancg.mpp.client.Document;
import io.github.christiancg.mpp.client.ScriptResult;
import io.github.christiancg.mpp.client.internal.ClientContext;
import io.github.christiancg.mpp.client.internal.Descriptors;
import io.github.christiancg.mpp.client.internal.ResponseDecoder;
import io.github.christiancg.mpp.client.internal.ScriptResults;
import io.github.christiancg.mpp.client.protocol.Operation;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

public final class Procedures {
    private final ClientContext context;
    private final String project;

    public Procedures(ClientContext context, String project) {
        this.context = context;
        this.project = project;
    }

    public long save(ProcedureDefinition procedure) {
        return context.channel().send(Operation.SAVE_PROCEDURE, context.scripts().saveProcedure(project, procedure))
                .path("version").asLong(0);
    }

    public void delete(String name) {
        context.channel().send(Operation.DELETE_PROCEDURE, context.scripts().deleteProcedure(project, name));
    }

    public List<ProcedureInfo> list() {
        return procedures(context.scripts().listProcedures(project));
    }

    public List<ProcedureInfo> list(boolean includeSource) {
        return procedures(context.scripts().listProcedures(project, includeSource));
    }

    public ScriptResult call(String name) {
        return run(context.scripts().callProcedure(project, name));
    }

    public ScriptResult call(String name, Document args) {
        return run(context.scripts().callProcedure(project, name, args));
    }

    private List<ProcedureInfo> procedures(ObjectNode request) {
        final var reply = context.channel().send(Operation.LIST_PROCEDURES, request);
        return ResponseDecoder.documents(reply, "procedures").stream().map(Descriptors::procedure).toList();
    }

    private ScriptResult run(ObjectNode request) {
        return ScriptResults.of(context.json(), context.channel().send(Operation.CALL_PROCEDURE, request));
    }
}
