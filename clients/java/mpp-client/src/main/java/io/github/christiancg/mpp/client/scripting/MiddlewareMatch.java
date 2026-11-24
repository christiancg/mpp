package io.github.christiancg.mpp.client.scripting;

import java.util.List;
import java.util.Objects;

public record MiddlewareMatch(MiddlewareCriteria include, List<MiddlewareCriteria> exclude) {
    public MiddlewareMatch {
        Objects.requireNonNull(include, "include");
        exclude = List.copyOf(exclude);
    }

    public static MiddlewareMatch of(MiddlewareCriteria include, MiddlewareCriteria... exclude) {
        return new MiddlewareMatch(include, List.of(exclude));
    }
}
