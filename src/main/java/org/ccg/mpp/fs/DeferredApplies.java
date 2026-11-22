package org.ccg.mpp.fs;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.ccg.mpp.config.Globals;

public final class DeferredApplies {
    private static final Set<String> unappliedLogs = ConcurrentHashMap.newKeySet();
    private static final Set<String> vacuumsBehind = ConcurrentHashMap.newKeySet();
    private static volatile boolean any;
    private static volatile DeferredApplyListener listener = DeferredApplyListener.NONE;

    private DeferredApplies() {
    }

    public static boolean pending(String collectionId) {
        return any && (unappliedLogs.contains(collectionId) || vacuumsBehind.contains(collectionId));
    }

    static synchronized void flag(String projectName, String collName) {
        unappliedLogs.add(idOf(projectName, collName));
        any = true;
    }

    static synchronized void flagBehindAVacuum(String projectName, String collName) {
        vacuumsBehind.add(idOf(projectName, collName));
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
            clearFlag(unappliedLogs, projectName, collName);
        }
    }

    static void clearBehindAVacuum(String projectName, String collName) {
        if (any) {
            clearFlag(vacuumsBehind, projectName, collName);
        }
    }

    private static synchronized void clearFlag(Set<String> flagged, String projectName, String collName) {
        flagged.remove(idOf(projectName, collName));
        any = !unappliedLogs.isEmpty() || !vacuumsBehind.isEmpty();
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
