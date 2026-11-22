package io.github.christiancg.mpp.client.scripting;

import io.github.christiancg.mpp.client.Document;
import java.util.Optional;

public record EntrypointInfo(String name, String method, String path, String url, String procedureName, long timeoutMs,
        boolean enabled, long version, Optional<String> definer, Optional<String> description,
        Optional<String> disabledReason, Document raw) {
}
