package org.ccg.mpp.ops;

import org.ccg.mpp.concurrency.SharedByteBudget;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;

public class AggregateHeldBudget extends SharedByteBudget {
    private static final Configuration configuration = Configuration.getInstance();

    public AggregateHeldBudget() {
        super(() -> configuration.sizeValue(ConfigKey.AGGREGATE_TOTAL_HELD_BYTES));
    }
}
