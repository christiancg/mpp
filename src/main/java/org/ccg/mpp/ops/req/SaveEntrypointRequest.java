package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class SaveEntrypointRequest extends VersionedDefinitionRequest {
    private String name;
    private String method;
    private String path;
    private String procedureName;
    private InlineProcedure procedure;
    private long timeoutMs;
    private String description;

    public SaveEntrypointRequest() {
        super(OperationType.SAVE_ENTRYPOINT, null, null);
    }

    public SaveEntrypointRequest(String projectName, String name, String method, String path) {
        super(OperationType.SAVE_ENTRYPOINT, projectName, null);
        this.name = name;
        this.method = method;
        this.path = path;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getProcedureName() {
        return procedureName;
    }

    public void setProcedureName(String procedureName) {
        this.procedureName = procedureName;
    }

    public InlineProcedure getProcedure() {
        return procedure;
    }

    public void setProcedure(InlineProcedure procedure) {
        this.procedure = procedure;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String boundProcedureName() {
        return procedure == null ? procedureName : procedure.getName();
    }
}
