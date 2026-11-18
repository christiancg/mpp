package io.github.christiancg.mpp.client.error;

public class ConnectionLostException extends MppException {
    public ConnectionLostException(String message, Throwable cause) {
        super(message, cause);
    }
}
