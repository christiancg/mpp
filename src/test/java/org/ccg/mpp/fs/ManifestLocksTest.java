package org.ccg.mpp.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.locks.ReentrantLock;
import org.ccg.mpp.data.FileWriteMode;
import org.ccg.mpp.data.ProjectFilePath;
import org.ccg.mpp.ex.ProjectFilesBusyException;
import org.ccg.mpp.test.ProjectFilesFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ManifestLocksTest {
    private static final String PROJECT = "lockproj";
    private static final ProjectFileWrites.WriteSpec REPLACE = new ProjectFileWrites.WriteSpec(FileWriteMode.REPLACE,
            null, true);
    private ProjectFileStore store;

    @BeforeEach
    void start() throws Exception {
        store = ProjectFilesFixture.start();
    }

    @AfterEach
    void stop() {
        store.useIncarnations(_ -> 0L);
        ProjectFilesFixture.stop();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void test_a_bounded_wait_gives_up_while_another_thread_holds_the_files() throws Exception {
        try (var _ = ManifestLockHolder.hold(store, PROJECT)) {
            final var replica = store.replica(PROJECT, 50L);
            assertThrows(ProjectFilesBusyException.class, () -> replica.applyFile("/f", 1L, bytes("x")));
            assertThrows(ProjectFilesBusyException.class, replica::describe);
            assertThrows(ProjectFilesBusyException.class,
                    () -> store.read(PROJECT, ProjectFilePath.parse("/f"), 0, 1, 50L));
            assertThrows(ProjectFilesBusyException.class, () -> store.collectTombstones(PROJECT, 1L, 50L));
        }
        assertTrue(store.replica(PROJECT, 50L).applyFile("/f", 1L, bytes("x")));
    }

    @Test
    public void test_a_retired_manifest_refuses_a_holder_that_kept_it() throws Exception {
        final var stale = store.writes(PROJECT);
        stale.write(ProjectFilePath.parse("/before"), bytes("b"), REPLACE, ProjectFilesFixture.nextVersion());
        store.quarantine(PROJECT, "a test");
        assertThrows(ProjectFilesBusyException.class, () -> stale.write(ProjectFilePath.parse("/after"), bytes("a"),
                REPLACE, ProjectFilesFixture.nextVersion()));
        assertFalse(Files.exists(store.root().resolve(PROJECT).resolve("after")));
        assertNull(store.stat(PROJECT, ProjectFilePath.parse("/before")));
    }

    @Test
    public void test_another_life_retires_the_manifest_its_writers_still_hold() throws Exception {
        store.useIncarnations(_ -> 3L);
        final var stale = store.writes(PROJECT);
        stale.write(ProjectFilePath.parse("/old"), bytes("o"), REPLACE, ProjectFilesFixture.nextVersion());
        store.useIncarnations(_ -> 4L);
        assertNull(store.stat(PROJECT, ProjectFilePath.parse("/old")));
        assertThrows(ProjectFilesBusyException.class, () -> stale.write(ProjectFilePath.parse("/late"), bytes("l"),
                REPLACE, ProjectFilesFixture.nextVersion()));
        assertNull(store.stat(PROJECT, ProjectFilePath.parse("/late")));
        assertEquals(4L, store.incarnation(PROJECT));
    }

    @Test
    public void test_an_interrupted_bounded_wait_reports_the_interrupt() throws Exception {
        final var lock = new ReentrantLock();
        final var holder = Thread.ofVirtual().start(() -> {
            lock.lock();
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        while (!lock.isLocked()) {
            Thread.onSpinWait();
        }
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> ManifestLocks.take(lock, PROJECT, 1_000L));
            assertTrue(Thread.interrupted());
        } finally {
            holder.interrupt();
            holder.join();
        }
    }
}
