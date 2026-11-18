package io.github.christiancg.mpp.client.error;

import io.github.christiancg.mpp.client.protocol.Status;

public class ConflictException extends MppException {
    public ConflictException(String message, String rawCode, Status status) {
        super(message, rawCode, status);
    }
}
