package org.ccg.mpp.fs;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ManifestLockHolder implements AutoCloseable {
    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final Thread holder;

    private ManifestLockHolder(FileManifest manifest) {
        holder = Thread.ofVirtual().start(() -> {
            manifest.lock().writeLock().lock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                manifest.lock().writeLock().unlock();
            }
        });
    }

    public static ManifestLockHolder hold(ProjectFileStore store, String projectName) throws Exception {
        final var holder = new ManifestLockHolder(store.manifest(projectName, ManifestLocks.UNBOUNDED));
        if (!holder.held.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the manifest lock was not taken");
        }
        return holder;
    }

    @Override
    public void close() throws Exception {
        release.countDown();
        holder.join();
    }
}
