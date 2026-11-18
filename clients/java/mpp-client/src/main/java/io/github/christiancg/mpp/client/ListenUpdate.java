package io.github.christiancg.mpp.client;

import java.util.List;

public record ListenUpdate<T>(String listenId, List<T> results, String resultHash, boolean initial) {
    public ListenUpdate {
        results = List.copyOf(results);
    }
}
