package org.ccg.mpp.test;

import java.io.File;
import java.io.IOException;
import org.ccg.mpp.cluster.HybridClock;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.fs.ProjectFileStore;
import org.ccg.mpp.ioc.IocContainer;

public final class ProjectFilesFixture {
    public static final String FILES_PATH = TestGlobals.PATH + "-files";
    private static final Configuration configuration = Configuration.getInstance();

    private ProjectFilesFixture() {
    }

    public static ProjectFileStore start() throws IOException {
        deleteIfPresent();
        configuration.override(ConfigKey.FILES_PATH, FILES_PATH);
        final var store = IocContainer.get(ProjectFileStore.class);
        final var clock = IocContainer.get(HybridClock.class);
        store.createRoot();
        store.useClock(clock::next, clock::observe);
        store.useIncarnations(_ -> 0L);
        return store;
    }

    public static void stop() {
        for (final var key : new ConfigKey[]{ConfigKey.FILES_PATH, ConfigKey.FILES_MAX_FILE_SIZE,
                ConfigKey.FILES_MAX_BYTES_PER_PROJECT, ConfigKey.FILES_MAX_ENTRIES_PER_PROJECT,
                ConfigKey.FILES_MAX_READ_BYTES, ConfigKey.CLUSTER_ENABLED}) {
            configuration.override(key, key.defaultValue());
        }
        deleteIfPresent();
    }

    private static void deleteIfPresent() {
        final var folder = new File(FILES_PATH);
        if (folder.exists()) {
            TestUtils.deleteFolder(folder);
        }
    }

    public static long nextVersion() {
        return IocContainer.get(HybridClock.class).next();
    }
}
