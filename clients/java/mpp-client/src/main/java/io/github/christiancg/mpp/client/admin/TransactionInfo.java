package io.github.christiancg.mpp.client.admin;

import io.github.christiancg.mpp.client.Document;
import java.util.List;
import java.util.Optional;

public record TransactionInfo(String dtxId, Optional<String> coordinator, boolean coordinatorReachable,
        List<String> participants, long ageMs, Document raw) {
}
