package org.ccg.mpp.ops.req;

import java.util.List;

public class MiddlewareMatchSpec extends MiddlewareCriteriaSpec {
    private List<MiddlewareCriteriaSpec> exclude;

    public List<MiddlewareCriteriaSpec> getExclude() {
        return exclude;
    }

    public void setExclude(List<MiddlewareCriteriaSpec> exclude) {
        this.exclude = exclude;
    }
}
