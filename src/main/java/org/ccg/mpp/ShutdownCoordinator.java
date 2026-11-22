package org.ccg.mpp;

import org.ccg.mpp.bckg_ops.BackgroundTaskManager;
import org.ccg.mpp.bckg_ops.ScheduleExecutor;
import org.ccg.mpp.bckg_ops.TriggerExecutor;
import org.ccg.mpp.cache.MemoryManagement;
import org.ccg.mpp.cluster.AdminAntiEntropyService;
import org.ccg.mpp.cluster.AntiEntropyService;
import org.ccg.mpp.cluster.ClusterConfig;
import org.ccg.mpp.cluster.ClusterServer;
import org.ccg.mpp.cluster.Tx2pcRecovery;
import org.ccg.mpp.cluster.membership.MembershipService;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.conn.InFlightRequests;
import org.ccg.mpp.conn.MetricsServer;
import org.ccg.mpp.conn.SocketServer;
import org.ccg.mpp.conn.WriteStallWatchdog;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.http.HttpServer;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.listen.ListenManager;
import org.ccg.mpp.log.LogWriter;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.ScriptRunHistory;
import org.ccg.mpp.ops.TransactionOperationHelper;
import org.ccg.mpp.ops.admin.PageRowWriteBack;
import org.ccg.mpp.ops.snapshot.SnapshotRegistry;
import org.ccg.mpp.ops.snapshot.SnapshotSweeper;

/**
 * The step order is the contract: refuse new work before draining so the queues can reach empty, release open
 * transactions before the drains so no queued event waits on a lock nobody will free, and leave the cluster last.
 */
public class ShutdownCoordinator {
    private final Logger logger = Logger.logFor(ShutdownCoordinator.class);
    private final Configuration configuration = Configuration.getInstance();
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final InFlightRequests inFlightRequests = IocContainer.get(InFlightRequests.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final ScheduleExecutor scheduleExecutor = IocContainer.get(ScheduleExecutor.class);
    private final BackgroundTaskManager backgroundTaskManager = IocContainer.get(BackgroundTaskManager.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final FileSystem fileSystem = IocContainer.get(FileSystem.class);
    private final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private final SnapshotRegistry snapshotRegistry = IocContainer.get(SnapshotRegistry.class);
    private final SnapshotSweeper snapshotSweeper = IocContainer.get(SnapshotSweeper.class);
    private final ScriptRunHistory scriptRunHistory = IocContainer.get(ScriptRunHistory.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final AntiEntropyService antiEntropyService = IocContainer.get(AntiEntropyService.class);
    private final AdminAntiEntropyService adminAntiEntropyService = IocContainer.get(AdminAntiEntropyService.class);
    private final Tx2pcRecovery tx2pcRecovery = IocContainer.get(Tx2pcRecovery.class);
    private final WriteStallWatchdog writeStallWatchdog = IocContainer.get(WriteStallWatchdog.class);
    private final MetricsServer metricsServer = IocContainer.get(MetricsServer.class);
    private final HttpServer httpServer = IocContainer.get(HttpServer.class);

    private volatile boolean alreadyRun;

    public void shutdown(SocketServer socketServer, ClusterServer clusterServer) {
        if (alreadyRun) {
            return;
        }
        alreadyRun = true;
        final var budget = configuration.getShutdownTimeoutMs();
        final var deadline = System.currentTimeMillis() + budget;
        logger.info("Shutting down; up to " + budget + "ms to finish outstanding work");

        step("stop accepting connections", () -> {
            if (socketServer != null) {
                socketServer.stopAccepting();
            }
            metricsServer.stop();
            httpServer.stopAccepting();
            if (clusterServer != null) {
                clusterServer.refuseWrites();
            }
        });
        step("stop background sweeps", () -> {
            memoryManagement.stopSweepThread();
            scriptRunHistory.stopSweep();
            triggerExecutor.stopSweep();
            scheduleExecutor.stopTicking();
            writeStallWatchdog.stop();
            snapshotSweeper.stop();
            if (clusterConfig.isEnabled()) {
                antiEntropyService.stop(remaining(deadline));
                adminAntiEntropyService.stop(remaining(deadline));
                tx2pcRecovery.stop();
            }
        });
        step("roll back open transactions", TransactionOperationHelper::rollbackOpenTransactionsAtShutdown);
        step("wait for in-flight requests", () -> awaitInFlightRequests(deadline));
        step("drain HTTP requests", () -> httpServer.drain(remaining(deadline)));
        step("release snapshots", snapshotRegistry::releaseAll);
        step("drain triggers", () -> triggerExecutor.drain(remaining(deadline)));
        // After the triggers and before the background queue: a scheduled run enqueues into both.
        step("drain schedules", () -> scheduleExecutor.drain(remaining(deadline)));
        step("drain background tasks", () -> backgroundTaskManager.drain(remaining(deadline)));
        step("flush page rows", PageRowWriteBack::flushAll);
        step("stop listen workers", listenManager::stopWorkers);
        step("checkpoint every write-ahead log", () -> fileSystem.checkpointWriteAheadLogs(deadline));
        step("leave the cluster", () -> {
            if (clusterConfig.isEnabled()) {
                membershipService.stop();
                if (clusterServer != null) {
                    clusterServer.stop();
                }
            }
        });
        logger.info("Shutdown complete");
        LogWriter.flushAndClose();
    }

    private void awaitInFlightRequests(long deadline) {
        try {
            if (!inFlightRequests.awaitIdle(remaining(deadline))) {
                logger.warning(inFlightRequests.current() + " request(s) still running at the end of the shutdown "
                        + "budget; their background work may be dropped");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void step(String description, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            logger.warning("Shutdown step '" + description + "' failed: " + e.getMessage());
        }
    }

    private static long remaining(long deadline) {
        return Math.max(200L, deadline - System.currentTimeMillis());
    }
}
