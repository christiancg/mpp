package org.ccg.mpp.simplejs.host;

import java.time.ZoneId;
import java.util.Locale;
import java.util.function.Consumer;
import org.ccg.mpp.ejson.elements.JsonObject;

public record MiddlewareHostBindings(ProjectHostBindings delegate,
        MiddlewareDecision decision) implements HostBindings {
    @Override
    public JsonObject args() {
        return delegate.args();
    }

    @Override
    public ProjectAccess project() {
        return delegate.project();
    }

    @Override
    public Consumer<String> console() {
        return delegate.console();
    }

    @Override
    public ResourceLimits limits() {
        return delegate.limits();
    }

    @Override
    public ModuleResolver moduleResolver() {
        return delegate.moduleResolver();
    }

    @Override
    public NetworkAccess network() {
        return delegate.network();
    }

    @Override
    public CancellationToken cancellation() {
        return delegate.cancellation();
    }

    @Override
    public ZoneId timeZone() {
        return delegate.timeZone();
    }

    @Override
    public Locale locale() {
        return delegate.locale();
    }

    @Override
    public MiddlewareDecision middlewareDecision() {
        return decision;
    }
}
