package io.github.christiancg.mpp.client.admin;

import io.github.christiancg.mpp.client.internal.ClientContext;
import io.github.christiancg.mpp.client.internal.Descriptors;
import io.github.christiancg.mpp.client.internal.ResponseDecoder;
import io.github.christiancg.mpp.client.protocol.Operation;
import java.util.List;

public final class DistributedTransactions {
    private final ClientContext context;

    DistributedTransactions(ClientContext context) {
        this.context = context;
    }

    public List<TransactionInfo> list() {
        final var reply = context.channel().send(Operation.LIST_TRANSACTIONS, context.scripts().listTransactions());
        return ResponseDecoder.documents(reply, "transactions").stream().map(Descriptors::transaction).toList();
    }

    public void resolve(String dtxId, TransactionDecision decision) {
        context.channel().send(Operation.RESOLVE_TRANSACTION, context.scripts().resolveTransaction(dtxId, decision));
    }
}
