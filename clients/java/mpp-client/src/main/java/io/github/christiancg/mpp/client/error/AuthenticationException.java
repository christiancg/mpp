package io.github.christiancg.mpp.client.error;

import io.github.christiancg.mpp.client.protocol.Status;

public class AuthenticationException extends MppException {
    public AuthenticationException(String message, String rawCode, Status status) {
        super(message, rawCode, status);
    }
}
