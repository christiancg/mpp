package io.github.christiancg.mpp.client.error;

public class PoolExhaustedException extends MppException {
    public PoolExhaustedException(String message) {
        super(message, null);
    }
}
