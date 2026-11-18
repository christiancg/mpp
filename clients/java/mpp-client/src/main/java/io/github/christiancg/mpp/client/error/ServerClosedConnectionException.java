package io.github.christiancg.mpp.client.error;

import io.github.christiancg.mpp.client.protocol.Status;

public class ServerClosedConnectionException extends MppException {
    public ServerClosedConnectionException(String message, String rawCode, Status status) {
        super(message, rawCode, status);
    }
}
