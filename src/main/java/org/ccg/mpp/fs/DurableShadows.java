package org.ccg.mpp.fs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.List;

public final class DurableShadows implements Forcer {
    static final String FILES_SHADOW_SUFFIX = "-files";
    private final List<DurableShadow> shadows;

    DurableShadows(List<DurableShadow> shadows) {
        this.shadows = shadows;
    }

    public static void install(Path dataRoot, Path filesRoot, Path shadowRoot) throws IOException {
        final var filesShadow = shadowRoot.resolveSibling(shadowRoot.getFileName() + FILES_SHADOW_SUFFIX);
        SyncedWrites.useForcer(new DurableShadows(
                List.of(DurableShadow.install(dataRoot, shadowRoot), DurableShadow.install(filesRoot, filesShadow))));
    }

    @Override
    public void force(FileChannel channel, Path path) throws IOException {
        final var shadow = covering(path);
        if (shadow == null) {
            Forcer.DEVICE.force(channel, path);
        } else {
            shadow.force(channel, path);
        }
    }

    @Override
    public void forceFailed(Path path) {
        final var shadow = covering(path);
        if (shadow != null) {
            shadow.forceFailed(path);
        }
    }

    private DurableShadow covering(Path path) {
        for (final var shadow : shadows) {
            if (shadow.covers(path)) {
                return shadow;
            }
        }
        return null;
    }
}
