package io.github.christiancg.mpp.client.internal;

import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.node.ObjectNode;

record PendingReply(CompletableFuture<ObjectNode> future, boolean awaitsClose) {
}
