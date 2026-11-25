package org.ccg.mpp.fs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DurableShadowsTest {
    @TempDir
    Path tmp;

    @AfterEach
    void restoreTheDevice() {
        SyncedWrites.useForcer(Forcer.DEVICE);
    }

    private static void forceThrough(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            SyncedWrites.forceOrFail(channel, file);
        }
    }

    private static boolean holdsABlobWith(Path shadowRoot, String content) throws IOException {
        final var blobs = shadowRoot.resolve(DurableShadow.BLOBS);
        try (var stored = Files.list(blobs)) {
            return stored.anyMatch(blob -> {
                try {
                    return Files.isRegularFile(blob) && Files.readString(blob).equals(content);
                } catch (IOException e) {
                    return false;
                }
            });
        }
    }

    @Test
    public void test_each_root_is_recorded_in_its_own_shadow() throws Exception {
        final var data = Files.createDirectories(tmp.resolve("db"));
        final var files = Files.createDirectories(tmp.resolve("files"));
        final var shadow = tmp.resolve("shadow");
        DurableShadows.install(data, files, shadow);
        final var page = Files.writeString(data.resolve("page.dat"), "page");
        final var upload = Files.writeString(files.resolve("upload.bin"), "upload");
        final var outside = Files.writeString(tmp.resolve("outside.txt"), "outside");
        forceThrough(page);
        forceThrough(upload);
        forceThrough(outside);
        final var filesShadow = tmp.resolve("shadow" + DurableShadows.FILES_SHADOW_SUFFIX);
        assertTrue(holdsABlobWith(shadow, "page"));
        assertFalse(holdsABlobWith(shadow, "upload"));
        assertTrue(holdsABlobWith(filesShadow, "upload"));
        assertFalse(holdsABlobWith(filesShadow, "outside"));
        final var single = DurableShadow.install(data, tmp.resolve("single"));
        final var forcer = new DurableShadows(List.of(single));
        forcer.forceFailed(outside);
        forcer.forceFailed(page);
        assertTrue(single.isFrozen(page));
        assertFalse(single.isFrozen(outside));
        single.close();
    }
}
