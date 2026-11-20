package org.ccg.mpp.cluster.admin;

import java.util.ArrayList;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.ClusterConfig;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.OnDiskNameRegistry;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.AdminStamp;
import org.ccg.mpp.ops.admin.GrantPruner;
import org.ccg.mpp.ops.admin.GrantRewrite;
import org.ccg.mpp.ops.admin.LeftoverFolders;
import org.ccg.mpp.ops.admin.ProjectOperationHelper;

final class ProjectRecordApplier {
    private static final Logger logger = Logger.logFor(ProjectRecordApplier.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final AdminQuarantine quarantine = new AdminQuarantine();

    private ProjectRecordApplier() {
    }

    static boolean install(AdminRecord record) throws Exception {
        final var projectName = record.key().projectName();
        final var waitMillis = clusterConfig.replicationAckTimeoutMs();
        if (!locks.tryLockProjectExclusive(projectName, waitMillis)) {
            logger.warning(skipping(projectName, "it stayed locked"));
            return false;
        }
        try {
            return installUnderNamesLock(record, waitMillis);
        } finally {
            locks.releaseProjectExclusive(projectName);
        }
    }

    private static boolean installUnderNamesLock(AdminRecord record, long waitMillis) throws Exception {
        final var projectName = record.key().projectName();
        if (!locks.tryLockWrite(Globals.ADMIN_PROJECT_NAME, Globals.PROJECT_NAMES_LOCK, waitMillis)) {
            logger.warning(skipping(projectName, "the project names lock stayed held"));
            return false;
        }
        try {
            final var sibling = caseVariantOf(projectName);
            if (sibling != null) {
                logger.warning("Skipping the install of project " + projectName + ": " + sibling.projectName()
                        + " was registered under the same folder meanwhile. The next round settles the pair by"
                        + " version.");
                return false;
            }
            return installUnderLocks(record);
        } finally {
            locks.release(Globals.ADMIN_PROJECT_NAME, Globals.PROJECT_NAMES_LOCK);
        }
    }

    private static boolean installUnderLocks(AdminRecord record) throws Exception {
        if (!AdminRecords.stillOutranks(record)) {
            return true;
        }
        final var projectName = record.key().projectName();
        final var local = cache.getAdminProjectEntry(projectName);
        if (local == null && LeftoverFolders.leftoverProjectStays(projectName)) {
            return false;
        }
        fs.createProjectFolder(projectName);
        final var owners = AdminProjectEntry.fromJsonObject(record.body()).getOwners();
        final var installed = new AdminProjectEntry(projectName,
                local == null ? new ArrayList<>() : new ArrayList<>(local.getCollections()), new ArrayList<>(owners));
        installed.setVersion(record.version());
        AdminOperationHelper.saveProjectEntry(installed);
        return true;
    }

    static AdminRecordKey caseVariantOf(String projectName) {
        final var sibling = OnDiskNameRegistry.collidingProject(projectName);
        if (sibling == null || cache.getAdminProjectEntry(sibling) == null) {
            return null;
        }
        return AdminRecordKey.project(sibling);
    }

    static boolean remove(AdminRecord tombstone, boolean live) throws Exception {
        final var key = tombstone.key();
        final var projectName = key.projectName();
        AdminStamp.tombstoneAt(key, tombstone.version());
        if (cache.getAdminProjectEntry(projectName) == null || !tombstone.outranks(AdminRecords.live(key))) {
            return true;
        }
        if (live) {
            return ProjectOperationHelper.dropProject(projectName, true, GrantRewrite.KEEP)
                    .getStatus() == OperationStatus.OK;
        }
        if (!quarantine.dropProject(projectName, clusterConfig.replicationAckTimeoutMs())) {
            return false;
        }
        GrantPruner.forDroppedProject(projectName, GrantRewrite.KEEP);
        return true;
    }

    private static String skipping(String projectName, String reason) {
        return "Skipping the install of project " + projectName + ": " + reason + " for "
                + clusterConfig.replicationAckTimeoutMs() + "ms. The next round retries it.";
    }
}
