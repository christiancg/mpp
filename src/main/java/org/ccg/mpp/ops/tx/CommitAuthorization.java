package org.ccg.mpp.ops.tx;

import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.Transaction;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.auth.AuthorizationChecker;

public final class CommitAuthorization {
    private static final Cache cache = IocContainer.get(Cache.class);

    private CommitAuthorization() {
    }

    public static boolean allows(Transaction transaction, String username) {
        if (username == null) {
            return true;
        }
        final var user = cache.getAdminUserEntry(username);
        for (final var collectionId : transaction.getHeldLocks()) {
            final var separator = collectionId.indexOf(Globals.COLL_IDENTIFIER_SEPARATOR);
            final var projectName = collectionId.substring(0, separator);
            final var collName = collectionId.substring(separator + 1);
            if (!AuthorizationChecker.mayWrite(user, projectName, collName)) {
                return false;
            }
        }
        return true;
    }
}
