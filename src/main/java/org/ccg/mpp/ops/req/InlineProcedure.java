package org.ccg.mpp.ops.req;

public class InlineProcedure {
    private String name;
    private String script;
    private String description;
    private Boolean enabled;

    public InlineProcedure() {
    }

    public InlineProcedure(String name, String script) {
        this.name = name;
        this.script = script;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public SaveProcedureRequest toSaveProcedureRequest(String projectName) {
        final var request = new SaveProcedureRequest(projectName, name, script);
        request.setDescription(description);
        request.setEnabled(isEnabled());
        return request;
    }
}
