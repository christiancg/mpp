package io.github.christiancg.mpp.client.admin;

import io.github.christiancg.mpp.client.internal.ClientContext;

public final class Admin {
    private final ClientContext context;

    public Admin(ClientContext context) {
        this.context = context;
    }

    public Users users() {
        return new Users(context);
    }

    public DistributedTransactions transactions() {
        return new DistributedTransactions(context);
    }

    public Scripts scripts() {
        return new Scripts(context);
    }

    public TriggerRuns triggerRuns() {
        return new TriggerRuns(context);
    }
}
