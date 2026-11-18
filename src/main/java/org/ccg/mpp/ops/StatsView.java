package org.ccg.mpp.ops;

import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.admin.DatabaseVisibility;
import org.ccg.mpp.ops.auth.AuthorizationChecker;

public record StatsView(AdminUserEntry user) {
    private static final Cache cache = IocContainer.get(Cache.class);

    public static StatsView of(AdminUserEntry user) {
        return new StatsView(user);
    }

    public boolean includesNodeSections() {
        return user() != null && user().isAdmin();
    }

    public boolean includesDatabase(String name) {
        return includesNodeSections() || DatabaseVisibility.isVisible(user(), cache.getAdminDbEntry(name));
    }

    public boolean includesCollection(String database, String collection) {
        return AuthorizationChecker.canReadCollection(user(), database, collection);
    }
}
