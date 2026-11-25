package org.ccg.mpp.fs;

import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import org.ccg.mpp.ex.ProjectFilesBusyException;

final class ManifestLocks {
    static final long UNBOUNDED = -1L;

    private ManifestLocks() {
    }

    static Lock read(FileManifest manifest, String projectName, long waitMillis)
            throws ProjectFilesBusyException, InterruptedIOException {
        return checked(manifest.lock().readLock(), manifest, projectName, waitMillis);
    }

    static Lock write(FileManifest manifest, String projectName, long waitMillis)
            throws ProjectFilesBusyException, InterruptedIOException {
        return checked(manifest.lock().writeLock(), manifest, projectName, waitMillis);
    }

    private static Lock checked(Lock lock, FileManifest manifest, String projectName, long waitMillis)
            throws ProjectFilesBusyException, InterruptedIOException {
        final var held = take(lock, projectName, waitMillis);
        if (manifest.isRetired()) {
            held.unlock();
            throw new ProjectFilesBusyException(projectName,
                    "they were moved aside, dropped or reloaded while this request waited; retry it");
        }
        return held;
    }

    static Lock take(Lock lock, String projectName, long waitMillis)
            throws ProjectFilesBusyException, InterruptedIOException {
        if (waitMillis < 0) {
            lock.lock();
            return lock;
        }
        try {
            if (lock.tryLock(waitMillis, TimeUnit.MILLISECONDS)) {
                return lock;
            }
            throw new ProjectFilesBusyException(projectName, "they stayed locked for " + waitMillis + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException(
                    "Interrupted while waiting for the files of project '" + projectName + "'");
        }
    }
}
