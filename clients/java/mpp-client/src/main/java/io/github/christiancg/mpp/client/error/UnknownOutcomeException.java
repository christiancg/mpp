package io.github.christiancg.mpp.client.error;

import io.github.christiancg.mpp.client.protocol.Status;

public class UnknownOutcomeException extends MppException {
    public UnknownOutcomeException(String message, String rawCode, Status status) {
        super(message, rawCode, status);
    }
}
