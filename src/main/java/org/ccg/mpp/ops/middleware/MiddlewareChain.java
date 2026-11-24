package org.ccg.mpp.ops.middleware;

import java.util.List;
import org.ccg.mpp.data.MiddlewareDefinition;

public record MiddlewareChain(List<MiddlewareDefinition> ingress, List<MiddlewareDefinition> egress,
        String unreadableReason) {
    public static final MiddlewareChain EMPTY = new MiddlewareChain(List.of(), List.of(), null);

    public MiddlewareChain {
        ingress = List.copyOf(ingress);
        egress = List.copyOf(egress);
    }

    public static MiddlewareChain unreadable(String reason) {
        return new MiddlewareChain(List.of(), List.of(), reason);
    }

    public boolean isUnreadable() {
        return unreadableReason != null;
    }
}
