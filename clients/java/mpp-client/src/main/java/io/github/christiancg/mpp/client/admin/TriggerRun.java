package io.github.christiancg.mpp.client.admin;

import io.github.christiancg.mpp.client.Document;
import java.util.Optional;

public record TriggerRun(String runId, String status, Optional<String> project, Optional<String> collection,
        Optional<String> trigger, Optional<String> procedure, Optional<String> event, long attempts,
        Optional<String> lastError, Document raw) {
}
