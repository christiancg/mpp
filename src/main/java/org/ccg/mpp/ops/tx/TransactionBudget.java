package org.ccg.mpp.ops.tx;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.ccg.mpp.concurrency.SharedByteBudget;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.Transaction;

public class TransactionBudget extends SharedByteBudget {
    private static final Configuration configuration = Configuration.getInstance();
    private final Map<Transaction, Long> reservedBy = new ConcurrentHashMap<>();

    public TransactionBudget() {
        super(() -> configuration.sizeValue(ConfigKey.TRANSACTION_TOTAL_MAX_BYTES));
    }

    public boolean tryReserveFor(Transaction transaction, long bytes) {
        if (!tryReserve(bytes)) {
            return false;
        }
        reservedBy.merge(transaction, bytes, Long::sum);
        return true;
    }

    public void releaseOf(Transaction transaction) {
        if (transaction != null) {
            final var reserved = reservedBy.remove(transaction);
            if (reserved != null) {
                release(reserved);
            }
        }
    }
}
