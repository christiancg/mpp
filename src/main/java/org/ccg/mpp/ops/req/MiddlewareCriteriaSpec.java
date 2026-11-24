package org.ccg.mpp.ops.req;

import java.util.List;

public class MiddlewareCriteriaSpec {
    private List<String> methods;
    private String pathPrefix;
    private List<String> entrypoints;

    public MiddlewareCriteriaSpec() {
    }

    public MiddlewareCriteriaSpec(List<String> methods, String pathPrefix, List<String> entrypoints) {
        this.methods = methods;
        this.pathPrefix = pathPrefix;
        this.entrypoints = entrypoints;
    }

    public List<String> getMethods() {
        return methods;
    }

    public void setMethods(List<String> methods) {
        this.methods = methods;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    public void setPathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public List<String> getEntrypoints() {
        return entrypoints;
    }

    public void setEntrypoints(List<String> entrypoints) {
        this.entrypoints = entrypoints;
    }
}
