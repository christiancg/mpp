package org.ccg.mpp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.IntConsumer;
import javax.net.ssl.SSLServerSocketFactory;
import org.ccg.mpp.bckg_ops.BackgroundTaskManager;
import org.ccg.mpp.bckg_ops.ScheduleExecutor;
import org.ccg.mpp.bckg_ops.ScheduleRegistry;
import org.ccg.mpp.bckg_ops.TriggerExecutor;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.MemoryManagement;
import org.ccg.mpp.cluster.AdminAntiEntropyService;
import org.ccg.mpp.cluster.AntiEntropyService;
import org.ccg.mpp.cluster.ClusterConfig;
import org.ccg.mpp.cluster.ClusterServer;
import org.ccg.mpp.cluster.FileAntiEntropyService;
import org.ccg.mpp.cluster.HybridClock;
import org.ccg.mpp.cluster.MetadataCachePruner;
import org.ccg.mpp.cluster.TransactionSessionReaper;
import org.ccg.mpp.cluster.Tx2pcRecovery;
import org.ccg.mpp.cluster.admin.AdminRecords;
import org.ccg.mpp.cluster.membership.MembershipService;
import org.ccg.mpp.cluster.ownership.OwnershipManager;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.config.ServerVersion;
import org.ccg.mpp.conn.MetricsServer;
import org.ccg.mpp.conn.SocketServer;
import org.ccg.mpp.conn.WriteStallWatchdog;
import org.ccg.mpp.conn.tls.TlsContextFactory;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.GlobalPermissionType;
import org.ccg.mpp.data.auth.PasswordHasher;
import org.ccg.mpp.ex.InvalidPortException;
import org.ccg.mpp.fs.DurableShadows;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.ProjectFileStore;
import org.ccg.mpp.http.HttpLimits;
import org.ccg.mpp.http.HttpServer;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.listen.ListenManager;
import org.ccg.mpp.log.LogWriter;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.EntrypointDispatcher;
import org.ccg.mpp.ops.ScheduleDispatcher;
import org.ccg.mpp.ops.ScriptRunHistory;
import org.ccg.mpp.ops.TransactionOperationHelper;
import org.ccg.mpp.ops.TriggerDispatcher;
import org.ccg.mpp.ops.TriggerRunRecovery;
import org.ccg.mpp.ops.admin.AdminStamp;
import org.ccg.mpp.ops.admin.DeferredWrites;
import org.ccg.mpp.ops.admin.PageOccupancyReconciler;
import org.ccg.mpp.ops.entrypoint.EntrypointRouter;
import org.ccg.mpp.ops.middleware.MiddlewareChains;
import org.ccg.mpp.ops.snapshot.SnapshotRegistry;
import org.ccg.mpp.ops.snapshot.SnapshotSweeper;
import org.ccg.mpp.simplejs.host.ScriptHeapGuard;

public class ServerMain {
    private static final Configuration config = Configuration.getInstance();
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private static final BackgroundTaskManager backgroundTaskManager = IocContainer.get(BackgroundTaskManager.class);
    private static final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private static final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);
    private static final ScheduleExecutor scheduleExecutor = IocContainer.get(ScheduleExecutor.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final HybridClock hybridClock = IocContainer.get(HybridClock.class);
    private static final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private static final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private static final MetadataCachePruner metadataCachePruner = IocContainer.get(MetadataCachePruner.class);
    private static final ShutdownCoordinator shutdownCoordinator = IocContainer.get(ShutdownCoordinator.class);
    private static final WriteStallWatchdog writeStallWatchdog = IocContainer.get(WriteStallWatchdog.class);
    private static final MetricsServer metricsServer = IocContainer.get(MetricsServer.class);
    private static final HttpServer httpServer = IocContainer.get(HttpServer.class);
    private static final EntrypointRouter entrypointRouter = IocContainer.get(EntrypointRouter.class);
    private static final MiddlewareChains middlewareChains = IocContainer.get(MiddlewareChains.class);
    private static final SnapshotRegistry snapshotRegistry = IocContainer.get(SnapshotRegistry.class);
    private static final SnapshotSweeper snapshotSweeper = IocContainer.get(SnapshotSweeper.class);
    private static ClusterServer clusterServer;
    private static final AntiEntropyService antiEntropyService = IocContainer.get(AntiEntropyService.class);
    private static final FileAntiEntropyService fileAntiEntropyService = IocContainer.get(FileAntiEntropyService.class);
    private static final ProjectFileStore projectFiles = IocContainer.get(ProjectFileStore.class);
    private static final AdminAntiEntropyService adminAntiEntropyService = IocContainer
            .get(AdminAntiEntropyService.class);
    private static final TransactionSessionReaper transactionSessionReaper = IocContainer
            .get(TransactionSessionReaper.class);
    private static final Tx2pcRecovery tx2pcRecovery = IocContainer.get(Tx2pcRecovery.class);
    private static final ScriptRunHistory scriptRunHistory = IocContainer.get(ScriptRunHistory.class);
    private static final Logger logger = Logger.logFor(ServerMain.class);
    private static final int BIND_FAILURE_STATUS = 1;
    private static final int STARTUP_FAILURE_STATUS = 1;
    @SuppressWarnings("FieldMayBeFinal")
    private static volatile IntConsumer exit = System::exit;

    private static int getPort(String[] args) {
        if (args.length > 0) {
            try {
                return Integer.parseInt(args[0]);
            } catch (Exception e) {
                throw new InvalidPortException(args[0], e);
            }
        } else {
            return config.getPort();
        }
    }

    private static void installDurableShadowIfConfigured() throws IOException {
        final var shadow = config.getPowerLossShadowPath();
        if (config.isFaultInjectionEnabled() && !shadow.isBlank()) {
            DurableShadows.install(Path.of(config.getFilePath()), projectFiles.root(), Path.of(shadow));
        }
    }

    public static void main(String[] args) {
        final SocketServer server;
        try {
            server = start(args);
        } catch (IOException | UncheckedIOException e) {
            logger.fatal("The server will not start: " + e.getMessage());
            exit.accept(STARTUP_FAILURE_STATUS);
            return;
        }
        registerShutdownHook(server);
        if (!server.serve()) {
            exit.accept(BIND_FAILURE_STATUS);
        }
    }

    private static void clearSnapshotsOfAPreviousProcess() {
        if (!fs.snapshotFiles().clearSnapshotFolders()) {
            logger.warning("Could not delete the snapshot images a previous process left under "
                    + Globals.SNAPSHOT_FOLDER_NAME + "; they hold nothing this process reads");
        }
        fs.snapshotFiles().onRetiredCopies(snapshotRegistry);
    }

    private static SocketServer start(String[] args) throws IOException {
        LogWriter.createLogPathAndRemoveOldFiles();
        logger.info("MPP " + ServerVersion.current() + " starting");
        AdminPasswordGuard.refuseWeakPassword();
        FetchPinningGuard.refuseUnpinnedFetch();
        ScriptHeapGuard.setCeilingPercent(config.intValue(ConfigKey.SCRIPT_HEAP_CEILING_PERCENT));
        fs.createBaseDbPath();
        StorageFormatGuard.refuseIncompatibleFolder(fs);
        clearSnapshotsOfAPreviousProcess();
        DeviceSyncGuard.fallBackWhenUnsyncable(fs);
        prepareProjectFiles();
        installDurableShadowIfConfigured();
        StorageFormatGuard.stampIfUnstamped(fs);
        fs.createAdminProject();
        StartupWarnings.retainAndReportUncleanStops();
        final var replayed = PageOccupancyReconciler.markReplayed(fs.redoWriteAheadLogs());
        DeferredWrites.install();
        fs.recoverWriteIntents();
        cache.loadAdminData();
        AdminPasswordGuard.refuseUnsetPasswordWhenBootstrapping(cache);
        seedHybridClock();
        AdminRecords.stampUnversioned();
        PageOccupancyReconciler.reconcileAll();
        relistUnlistedCollections();
        PageOccupancyReconciler.scheduleIndexRepairFor(replayed);
        cleanupOrphanedTransactions();
        bootstrapDefaultAdmin();
        PasswordHasher.dummyVerify("");
        final var port = getPort(args);
        backgroundTaskManager.startBackgroundWorkers();
        final var startupTriggerRuns = TriggerRunRecovery.startupRunIds();
        triggerExecutor.start(TriggerDispatcher::dispatch);
        // Must run after cleanupOrphanedTransactions: the records left are runs that never applied.
        TriggerRunRecovery.garbageCollect();
        TriggerRunRecovery.warnAboutStrandedRuns();
        startTriggerRunSweepIfStandalone();
        startSchedulerIfEnabled();
        listenManager.startWorkers();
        memoryManagement.loadProfileFromAdmin();
        memoryManagement.startSweepThread();
        scriptRunHistory.startSweep();
        writeStallWatchdog.start();
        snapshotSweeper.start();
        StartupWarnings.warnIfMaxMemoryClamped();
        StartupWarnings.warnIfXmxExceedsMaxMemory();
        StartupWarnings.warnIfCachesExceedHeap();
        StartupWarnings.warnIfCollectionsOutgrowMaxMemory();
        StartupWarnings.warnIfScriptFetchEnabled();
        StartupWarnings.warnIfPlaintextClients();
        StartupWarnings.warnIfDefaultKeystorePassword();
        StartupWarnings.warnIfProtectiveLimitsAreOff();
        StartupWarnings.warnIfStorageIsReadableByOthers();
        StartupWarnings.warnIfFaultInjectionEnabled();
        StartupWarnings.warnIfWritesAreNotSynced();
        StartupWarnings.warnIfClusterWithoutTls();
        StartupWarnings.warnIfMetricsExposedWithoutToken();
        StartupWarnings.warnIfNamesShareAnOnDiskKey();
        StartupWarnings.warnIfProjectSharesTheClusterFolder();
        startMetricsIfEnabled();
        final var tlsFactory = createTlsFactory();
        startEntrypointsIfEnabled(tlsFactory);
        startClusterIfEnabled();
        TriggerRunRecovery.recoverLocal(startupTriggerRuns);
        return new SocketServer(port, config.getBindAddress(), tlsFactory);
    }

    private static void startEntrypointsIfEnabled(SSLServerSocketFactory tlsFactory) throws IOException {
        final var httpPort = config.intValue(ConfigKey.HTTP_PORT);
        if (httpPort == 0) {
            return;
        }
        try {
            entrypointRouter.loadAll();
        } catch (RuntimeException e) {
            logger.error("Failed to load the entrypoint routes; the listener starts with the ones that loaded", e);
        }
        middlewareChains.loadAll();
        final var limits = new HttpLimits(config.intValue(ConfigKey.HTTP_MAX_CONNECTIONS),
                config.sizeValue(ConfigKey.HTTP_MAX_REQUEST_BYTES), config.longValue(ConfigKey.HTTP_READ_TIMEOUT_MS),
                config.longValue(ConfigKey.HTTP_IDLE_TIMEOUT_MS));
        httpServer.start(httpPort, config.stringValue(ConfigKey.HTTP_BIND_ADDRESS).trim(), tlsFactory,
                List.of(TlsContextFactory.enabledProtocols()), limits, EntrypointDispatcher::handle);
    }

    private static void startMetricsIfEnabled() throws IOException {
        if (config.intValue(ConfigKey.METRICS_PORT) != 0) {
            metricsServer.start();
        }
    }

    private static void startTriggerRunSweepIfStandalone() {
        if (!clusterConfig.isEnabled()) {
            triggerExecutor.sweepPeriodically(TriggerRunRecovery::sweep,
                    TriggerRunRecovery.STANDALONE_SWEEP_INTERVAL_MS);
        }
    }

    private static void startSchedulerIfEnabled() {
        if (!config.isSchedulesEnabled()) {
            return;
        }
        try {
            scheduleRegistry.loadAll();
        } catch (RuntimeException e) {
            logger.error("Failed to load the schedule definitions; the scheduler starts with the ones that loaded", e);
        }
        scheduleExecutor.start(ScheduleDispatcher::dispatch);
    }

    private static void registerShutdownHook(SocketServer server) {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> shutdownCoordinator.shutdown(server, clusterServer), "shutdown"));
    }

    private static void startClusterIfEnabled() {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        try {
            final var factory = clusterConfig.tlsEnabled()
                    ? TlsContextFactory.createClusterServerSocketFactory(config)
                    : null;
            clusterServer = new ClusterServer(clusterConfig.clusterPort(), clusterConfig.bindAddress(), factory);
            clusterServer.start();
            membershipService.addListener(ownershipManager);
            // Listeners fire in registration order: this must follow the ownership manager to read the rebuilt ring.
            membershipService.addListener(metadataCachePruner);
            // Admin listener before the document one: structure must conform before documents repopulate it.
            membershipService.addListener(adminAntiEntropyService);
            membershipService.addListener(antiEntropyService);
            membershipService.addListener(fileAntiEntropyService);
            membershipService.addListener(transactionSessionReaper);
            membershipService.addListener(tx2pcRecovery);
            membershipService.start();
            ownershipManager.setSelfNodeId(membershipService.getSelf().getNodeId());
            adminAntiEntropyService.start();
            antiEntropyService.start();
            fileAntiEntropyService.start();
            recoverTransactionsAtStartup();
            tx2pcRecovery.start();
        } catch (IOException e) {
            logger.fatal("Failed to start the cluster server", e);
            throw new RuntimeException("Failed to start the cluster server", e);
        }
    }

    private static void prepareProjectFiles() throws IOException {
        projectFiles.useConfiguredRoot();
        StorageFormatGuard.refuseIncompatibleFiles(projectFiles);
        projectFiles.createRoot();
        projectFiles.useClock(hybridClock::next, hybridClock::observe);
        projectFiles.useIncarnations(projectName -> {
            final var project = cache.getAdminProjectEntry(projectName);
            return project == null ? 0L : project.getIncarnation();
        });
        StorageFormatGuard.stampFilesIfUnstamped(projectFiles);
    }

    private static void recoverTransactionsAtStartup() {
        try {
            tx2pcRecovery.recoverNow();
        } catch (Exception failure) {
            logger.error("Failed to recover in-doubt transactions at startup", failure);
        }
    }

    private static void seedHybridClock() {
        var highest = Math.max(highestBufferedOpVersionOrZero(), highestAdminVersionOrZero());
        try {
            for (final var projectName : cache.getUserProjectNames()) {
                for (final var collName : cache.getCollectionNamesForProject(projectName)) {
                    highest = Math.max(highest, highestVersionOrZero(projectName, collName));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to seed the write clock from disk; versions could regress after this restart", e);
            return;
        }
        hybridClock.observe(highest);
        logger.info("Seeded the write clock from disk at version " + highest);
    }

    private static long highestAdminVersionOrZero() {
        var highest = 0L;
        try {
            for (final var record : AdminRecords.all()) {
                highest = Math.max(highest, record.version());
            }
        } catch (Exception e) {
            logger.error("Could not seed the write clock from the admin records; an admin write after this restart"
                    + " could be stamped below a version it replaces", e);
        }
        return highest;
    }

    private static long highestBufferedOpVersionOrZero() {
        var highest = 0L;
        try {
            final var ops = AdminOperationHelper
                    .readTransactionOps(List.copyOf(cache.getTransactionPkIndexes().keySet()));
            for (final var op : ops) {
                for (final var version : op.getVersions()) {
                    highest = Math.max(highest, version);
                }
            }
        } catch (Exception e) {
            logger.error("Could not seed the write clock from the buffered transaction ops; a replay after this"
                    + " restart could compare against regressed versions", e);
        }
        return highest;
    }

    private static long highestVersionOrZero(String projectName, String collName) {
        var highest = 0L;
        try {
            final var collEntry = cache.getAdminCollectionEntry(projectName, collName);
            if (collEntry != null) {
                highest = Math.max(highest, collEntry.getIncarnation());
            }
            highest = Math.max(highest, fs.highestPkVersion(projectName, collName));
            highest = Math.max(highest, fs.tombstones().highestVersion(projectName, collName));
        } catch (Exception e) {
            logger.error("Could not seed the write clock from " + projectName + "|" + collName
                    + "; versions written to it could regress after this restart", e);
        }
        return highest;
    }

    private static void relistUnlistedCollections() {
        try {
            AdminOperationHelper.relistUnlistedCollections();
        } catch (Exception failure) {
            logger.error("Failed to re-list registered collections missing from their project's list", failure);
        }
    }

    private static void cleanupOrphanedTransactions() {
        try {
            tx2pcRecovery.onRecoveryThread(() -> {
                TransactionOperationHelper.cleanupOrphansAtStartup();
                return null;
            }, 0L);
        } catch (Throwable failure) {
            logger.error("Failed to clean up orphaned transactions at startup", failure);
        }
    }

    static SSLServerSocketFactory createTlsFactory() {
        if (!config.isTlsEnabled()) {
            return null;
        }
        return TlsContextFactory.createServerSocketFactory(config);
    }

    private static void bootstrapDefaultAdmin() throws IOException {
        final var existingAdmins = cache.getAllAdminUserEntries().stream().filter(AdminUserEntry::isAdmin).count();

        if (existingAdmins > 0) {
            return;
        }

        final var defaultUsername = config.getDefaultAdminUsername();
        final var defaultPassword = config.getDefaultAdminPassword();

        final var passwordHash = PasswordHasher.hash(defaultPassword);
        final var globalPerms = new HashSet<GlobalPermissionType>();
        globalPerms.add(GlobalPermissionType.CREATE_PROJECT);
        globalPerms.add(GlobalPermissionType.DROP_PROJECT);

        final var adminUser = new AdminUserEntry(defaultUsername, passwordHash, true, globalPerms, new HashMap<>(),
                new HashMap<>());

        try {
            adminUser.setVersion(AdminStamp.BOOTSTRAP_VERSION);
            AdminOperationHelper.saveUserEntry(adminUser);
            logger.info("Bootstrapped default admin user: " + defaultUsername);
        } catch (InterruptedException e) {
            throw new RuntimeException("Failed to bootstrap admin user", e);
        }
    }
}
