package io.github.christiancg.mpp.client.scripting;

import io.github.christiancg.mpp.client.Document;
import java.util.Optional;

public record MiddlewareInfo(String name, String phase, long order, Optional<MiddlewareMatch> match,
        String procedureName, long timeoutMs, boolean enabled, long version, Optional<String> definer,
        Optional<String> description, Optional<String> disabledReason, Document raw) {
}
