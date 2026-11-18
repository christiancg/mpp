package io.github.christiancg.mpp.client.query;

import java.util.Objects;

public record ScriptOperation(String source) implements MidOperation {
    public ScriptOperation {
        Objects.requireNonNull(source, "source");
    }
}
