package io.github.christiancg.mpp.client.error;

import io.github.christiancg.mpp.client.protocol.Status;

public class UnavailableException extends MppException {
    public UnavailableException(String message, String rawCode, Status status) {
        super(message, rawCode, status);
    }
}
