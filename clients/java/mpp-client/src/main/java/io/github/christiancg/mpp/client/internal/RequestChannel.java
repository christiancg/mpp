package io.github.christiancg.mpp.client.internal;

import io.github.christiancg.mpp.client.protocol.Operation;
import tools.jackson.databind.node.ObjectNode;

public interface RequestChannel {
    ObjectNode send(Operation operation, ObjectNode request);
}
