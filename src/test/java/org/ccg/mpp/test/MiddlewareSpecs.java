package org.ccg.mpp.test;

import java.util.List;
import org.ccg.mpp.ops.req.MiddlewareCriteriaSpec;
import org.ccg.mpp.ops.req.MiddlewareMatchSpec;

public final class MiddlewareSpecs {
    private MiddlewareSpecs() {
    }

    public static MiddlewareMatchSpec match(List<String> methods, String pathPrefix, List<String> entrypoints,
            List<MiddlewareCriteriaSpec> exclude) {
        final var match = new MiddlewareMatchSpec();
        match.setMethods(methods);
        match.setPathPrefix(pathPrefix);
        match.setEntrypoints(entrypoints);
        match.setExclude(exclude);
        return match;
    }
}
