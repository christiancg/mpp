package io.github.christiancg.mpp.spring.data.transaction;

import io.github.christiancg.mpp.client.MppClient;
import io.github.christiancg.mpp.client.error.MppException;
import io.github.christiancg.mpp.client.protocol.ErrorCode;
import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class MppTransactionManager extends AbstractPlatformTransactionManager implements ResourceTransactionManager {
    @Serial
    private static final long serialVersionUID = 1L;

    private final transient MppClient client;

    public MppTransactionManager(MppClient client) {
        this.client = Objects.requireNonNull(client, "client");
        setNestedTransactionAllowed(false);
    }

    @Override
    public Object getResourceFactory() {
        return client;
    }

    @Override
    protected Object doGetTransaction() {
        return new TransactionObject((MppTransactionHolder) TransactionSynchronizationManager.getResource(client));
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return ((TransactionObject) transaction).holder != null;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        final MppTransactionHolder holder;
        try {
            holder = new MppTransactionHolder(client.beginTransaction());
        } catch (MppException e) {
            throw new CannotCreateTransactionException("could not start an MPP transaction", e);
        }
        holder.setSynchronizedWithTransaction(true);
        ((TransactionObject) transaction).holder = holder;
        TransactionSynchronizationManager.bindResource(client, holder);
    }

    @Override
    protected Object doSuspend(Object transaction) {
        ((TransactionObject) transaction).holder = null;
        return TransactionSynchronizationManager.unbindResource(client);
    }

    @Override
    protected void doResume(@Nullable Object transaction, Object suspendedResources) {
        TransactionSynchronizationManager.bindResource(client, suspendedResources);
    }

    @Override
    @SuppressWarnings("resource")
    protected void doCommit(DefaultTransactionStatus status) {
        try {
            holderOf(status).transaction().commit();
        } catch (MppException e) {
            throw new TransactionSystemException("could not commit the MPP transaction", e);
        }
    }

    @Override
    @SuppressWarnings("resource")
    protected void doRollback(DefaultTransactionStatus status) {
        try {
            holderOf(status).transaction().rollback();
        } catch (MppException e) {
            if (!e.hasCode(ErrorCode.NO_ACTIVE_TRANSACTION)) {
                throw new TransactionSystemException("could not roll back the MPP transaction", e);
            }
        }
    }

    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) {
        holderOf(status).setRollbackOnly();
    }

    @Override
    @SuppressWarnings("resource")
    protected void doCleanupAfterCompletion(Object transaction) {
        final var holder = ((TransactionObject) transaction).holder;
        TransactionSynchronizationManager.unbindResourceIfPossible(client);
        if (holder != null && holder.transaction().isOpen()) {
            try {
                holder.transaction().close();
            } catch (MppException ignored) {
            }
        }
    }

    private static MppTransactionHolder holderOf(DefaultTransactionStatus status) {
        return Objects.requireNonNull(((TransactionObject) status.getTransaction()).holder, "no MPP transaction");
    }

    private static final class TransactionObject {
        private @Nullable MppTransactionHolder holder;

        TransactionObject(@Nullable MppTransactionHolder holder) {
            this.holder = holder;
        }
    }
}
