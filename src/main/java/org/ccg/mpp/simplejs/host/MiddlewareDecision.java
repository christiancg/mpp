package org.ccg.mpp.simplejs.host;

import java.util.Set;
import org.ccg.mpp.data.MiddlewarePhase;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonObject;

public final class MiddlewareDecision {
    public sealed interface Outcome {
        record Next(JsonObject changes) implements Outcome {
        }

        record Reject(int status, JsonBaseElement body, JsonObject headers) implements Outcome {
        }
    }

    private final MiddlewarePhase phase;
    private final Set<String> declaredPathParams;
    private Outcome outcome;

    public MiddlewareDecision(MiddlewarePhase phase, Set<String> declaredPathParams) {
        this.phase = phase;
        this.declaredPathParams = Set.copyOf(declaredPathParams);
    }

    public synchronized void decide(Outcome decided) {
        if (outcome != null) {
            throw new IllegalStateException("the middleware already decided");
        }
        outcome = decided;
    }

    public synchronized Outcome outcome() {
        return outcome;
    }

    public MiddlewarePhase phase() {
        return phase;
    }

    public Set<String> declaredPathParams() {
        return declaredPathParams;
    }
}
