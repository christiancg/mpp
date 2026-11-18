package io.github.christiancg.mpp.client;

import io.github.christiancg.mpp.client.error.ListenEndedException;

public interface ListenHandler<T> {
    void onUpdate(ListenUpdate<T> update);

    default void onEnded(ListenEndedException reason) {
    }

    default void onError(Throwable error) {
    }
}
