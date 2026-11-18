package io.github.christiancg.mpp.client;

@FunctionalInterface
public interface TransactionBody<R> {
    R run(Transaction transaction);
}
