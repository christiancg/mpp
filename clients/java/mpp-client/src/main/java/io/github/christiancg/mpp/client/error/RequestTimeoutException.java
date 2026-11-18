package io.github.christiancg.mpp.client.error;

public class RequestTimeoutException extends ConnectionLostException {
    public RequestTimeoutException(String message) {
        super(message, null);
    }
}
