package io.github.christiancg.mpp.client;

public interface Subscription extends AutoCloseable {
    String listenId();

    boolean isActive();

    @Override
    void close();
}
