package io.github.christiancg.mpp.spring.data.transaction;

import io.github.christiancg.mpp.client.Transaction;
import org.springframework.transaction.support.ResourceHolderSupport;

public class MppTransactionHolder extends ResourceHolderSupport {
    private final Transaction transaction;

    public MppTransactionHolder(Transaction transaction) {
        this.transaction = transaction;
    }

    public Transaction transaction() {
        return transaction;
    }
}
