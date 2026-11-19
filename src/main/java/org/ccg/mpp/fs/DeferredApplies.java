package org.ccg.mpp.fs;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.ccg.mpp.config.Globals;

public final class DeferredApplies {
    private static final Set<String> collections = ConcurrentHashMap.newKeySet();
    private static volatile boolean any;
    private static volatile DeferredApplyListener listener = DeferredApplyListener.NONE;

    private DeferredApplies() {
    }

    public static boolean pending(String collectionId) {
        return any && collections.contains(collectionId);
    }

    static synchronized void flag(String projectName, String collName) {
        collections.add(idOf(projectName, collName));
        any = true;
    }

    static void flagIfUnapplied(FilePaths paths, String projectName, String collName) {
        final var state = WalLogState.known(paths.writeAheadLog(projectName, collName).getAbsolutePath());
        if (state != null && state.unapplied) {
            flag(projectName, collName);
        }
    }

    static void clear(String projectName, String collName) {
        if (any) {
            clearFlag(projectName, collName);
        }
    }

    private static synchronized void clearFlag(String projectName, String collName) {
        collections.remove(idOf(projectName, collName));
        any = !collections.isEmpty();
    }

    static void listenWith(DeferredApplyListener replacement) {
        listener = replacement == null ? DeferredApplyListener.NONE : replacement;
    }

    static void replay(FilePaths paths, String projectName, String collName, WalCodec.Decoded decoded)
            throws IOException {
        final var rows = WalRedo.writtenRows(projectName, collName, decoded.records());
        final var current = listener;
        final var generation = current.replaying(projectName, collName, rows);
        try {
            WalRedo.replay(paths, projectName, collName, decoded);
        } catch (IOException | RuntimeException failure) {
            current.notReplayed(projectName, collName, rows, generation);
            throw failure;
        }
        current.replayed(projectName, collName, rows, generation);
    }

    private static String idOf(String projectName, String collName) {
        return projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }
}
