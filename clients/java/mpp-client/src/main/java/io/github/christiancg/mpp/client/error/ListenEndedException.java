package io.github.christiancg.mpp.client.error;

import io.github.christiancg.mpp.client.protocol.Status;

public class ListenEndedException extends MppException {
    public ListenEndedException(String message, String rawCode, Status status) {
        super(message, rawCode, status);
    }
}
