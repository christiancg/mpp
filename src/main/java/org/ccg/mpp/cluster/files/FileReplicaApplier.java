package org.ccg.mpp.cluster.files;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.HybridClock;
import org.ccg.mpp.cluster.msg.FilePayload;
import org.ccg.mpp.cluster.msg.FileReplicationOp;
import org.ccg.mpp.data.ProjectFilePath;
import org.ccg.mpp.ex.InvalidFilePathException;
import org.ccg.mpp.fs.FileChange;
import org.ccg.mpp.fs.ProjectFileReplica;
import org.ccg.mpp.fs.ProjectFileStore;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.req.validations.NameValidations;

public final class FileReplicaApplier {
    private static final ProjectFileStore store = IocContainer.get(ProjectFileStore.class);
    private static final HybridClock hybridClock = IocContainer.get(HybridClock.class);
    private static final Cache cache = IocContainer.get(Cache.class);

    private FileReplicaApplier() {
    }

    public static boolean isValid(FilePayload payload) {
        if (payload == null || !NameValidations.isName(payload.getProjectName())
                || NameValidations.isReservedProjectName(payload.getProjectName())
                || FilePayloads.longOf(payload.getIncarnation()) < 0) {
            return false;
        }
        if (payload.getPath() != null && isMalformed(payload.getPath())) {
            return false;
        }
        if (payload.getOps() == null) {
            return true;
        }
        for (final var op : payload.getOps()) {
            if (op == null || isMalformed(op.getPath()) || op.getTarget() != null && isMalformed(op.getTarget())
                    || FilePayloads.longOf(op.getVersion()) <= 0 || FilePayloads.longOf(op.getBaseVersion()) < 0
                    || op.getSize() < 0 || op.getBaseSize() < 0) {
                return false;
            }
        }
        return true;
    }

    static boolean isMalformed(String path) {
        try {
            final var parsed = ProjectFilePath.parse(path);
            return parsed.isRoot() || !parsed.toString().equals(path);
        } catch (InvalidFilePathException e) {
            return true;
        }
    }

    public static boolean sameLife(FilePayload payload) {
        final var project = cache.getAdminProjectEntry(payload.getProjectName());
        if (project == null) {
            return false;
        }
        final var incoming = FilePayloads.longOf(payload.getIncarnation());
        return incoming == 0L || project.getIncarnation() == 0L || incoming == project.getIncarnation();
    }

    public static boolean apply(FilePayload payload, long waitMillis) throws IOException {
        final var replica = store.replica(payload.getProjectName(), waitMillis);
        for (final var op : payload.getOps()) {
            final var version = FilePayloads.longOf(op.getVersion());
            hybridClock.observe(version);
            if (!applyOne(replica, op, version)) {
                return false;
            }
        }
        return true;
    }

    private static boolean applyOne(ProjectFileReplica replica, FileReplicationOp op, long version) throws IOException {
        if (op.getType() == null) {
            return false;
        }
        final FileChange.Type type;
        try {
            type = FileChange.Type.valueOf(op.getType());
        } catch (IllegalArgumentException unknown) {
            return false;
        }
        return switch (type) {
            case FILE -> op.getContent() != null && replica.applyFile(op.getPath(), version, decode(op));
            case APPEND -> op.getContent() != null && replica.applyAppend(op.getPath(), version,
                    FilePayloads.longOf(op.getBaseVersion()), op.getBaseSize(), decode(op));
            case DIRECTORY -> replica.applyDirectory(op.getPath(), version);
            case TOMBSTONE -> replica.applyTombstone(op.getPath(), version);
            case MOVE -> op.getTarget() != null && replica.applyMove(op.getPath(), op.getTarget(), version);
        };
    }

    private static byte[] decode(FileReplicationOp op) {
        return Base64.getDecoder().decode(op.getContent());
    }

    public static List<FileReplicationOp> listing(String projectName, long waitMillis) throws IOException {
        return FilePayloads.listing(store.replica(projectName, waitMillis).describe());
    }
}
