package org.ccg.mpp.listen;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.ccg.mpp.ops.req.AggregateRequest;

public record ListenRegistration(UUID listenId, UUID clientId, AggregateRequest request, Set<String> collectionKeys,
        AtomicReference<String> lastHash, AtomicBoolean delivered, String username, long retainedBytes, UUID groupId) {
}
