package org.ccg.mpp.ops.req;

import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.OperationType;

public class RunScriptRequest extends OperationRequest {
    private String script;
    private JsonObject args;

    public RunScriptRequest() {
        super(OperationType.RUN_SCRIPT, null, null);
    }

    public RunScriptRequest(String databaseName, String script, JsonObject args) {
        super(OperationType.RUN_SCRIPT, databaseName, null);
        this.script = script;
        this.args = args;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }

    public JsonObject getArgs() {
        return args == null ? new JsonObject() : args;
    }

    public void setArgs(JsonObject args) {
        this.args = args;
    }
}
