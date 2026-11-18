package org.ccg.mpp.simplejs.host;

@FunctionalInterface
public interface CancellationToken {
    boolean isCancelled();
}
