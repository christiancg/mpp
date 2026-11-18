package io.github.christiancg.mpp.client.admin;

import io.github.christiancg.mpp.client.Document;
import java.util.Optional;

public record ScriptRun(String runId, Optional<String> node, String kind, Optional<String> database,
        Optional<String> name, Optional<String> username, long ageMs, Document raw) {
}
