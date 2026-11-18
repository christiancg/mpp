package io.github.christiancg.mpp.client.scripting;

import io.github.christiancg.mpp.client.Document;
import java.util.List;
import java.util.Optional;

public record TriggerTestResult(String decision, Optional<Document> document, List<String> logs,
        boolean logsTruncated) {
}
