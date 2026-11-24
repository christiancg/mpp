package org.ccg.mpp.ops.req;

import org.ccg.mpp.data.MiddlewarePhase;
import org.ccg.mpp.ops.OperationType;

public class SaveMiddlewareRequest extends VersionedDefinitionRequest {
    private String name;
    private MiddlewarePhase phase;
    private Integer order;
    private MiddlewareMatchSpec match;
    private String procedureName;
    private InlineProcedure procedure;
    private long timeoutMs;
    private String description;

    public SaveMiddlewareRequest() {
        super(OperationType.SAVE_MIDDLEWARE, null, null);
    }

    public SaveMiddlewareRequest(String projectName, String name, MiddlewarePhase phase) {
        super(OperationType.SAVE_MIDDLEWARE, projectName, null);
        this.name = name;
        this.phase = phase;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public MiddlewarePhase getPhase() {
        return phase;
    }

    public void setPhase(MiddlewarePhase phase) {
        this.phase = phase;
    }

    public int getOrder() {
        return order == null ? 0 : order;
    }

    public void setOrder(Integer order) {
        this.order = order;
    }

    public MiddlewareMatchSpec getMatch() {
        return match;
    }

    public void setMatch(MiddlewareMatchSpec match) {
        this.match = match;
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
