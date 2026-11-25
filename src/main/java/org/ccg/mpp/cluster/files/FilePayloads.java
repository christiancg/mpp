package org.ccg.mpp.cluster.files;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.ccg.mpp.cluster.msg.FilePayload;
import org.ccg.mpp.cluster.msg.FileReplicationOp;
import org.ccg.mpp.data.FileKind;
import org.ccg.mpp.fs.FileChange;
import org.ccg.mpp.fs.ProjectFileReplica;

public final class FilePayloads {
    public static final String TOMBSTONE = "TOMBSTONE";

    private FilePayloads() {
    }

    public static FilePayload replication(String projectName, long incarnation, List<FileChange> changes,
            byte[] content) {
        final var payload = header(projectName, incarnation);
        final var ops = new ArrayList<FileReplicationOp>(changes.size());
        for (final var change : changes) {
            final var op = new FileReplicationOp();
            op.setType(change.type().name());
            op.setPath(change.path());
            op.setTarget(change.target());
            op.setVersion(Long.toString(change.version()));
            op.setSize(change.size());
            if (change.type() == FileChange.Type.APPEND) {
                op.setBaseVersion(Long.toString(change.baseVersion()));
                op.setBaseSize(change.baseSize());
            }
            if (content != null && (change.type() == FileChange.Type.FILE || change.type() == FileChange.Type.APPEND)) {
                op.setContent(Base64.getEncoder().encodeToString(content));
            }
            ops.add(op);
        }
        payload.setOps(ops);
        return payload;
    }

    public static String summary(List<FileReplicationOp> listing) {
        final var described = new ArrayList<ProjectFileReplica.Described>(listing.size());
        for (final var op : listing) {
            final var tombstone = TOMBSTONE.equals(op.getType());
            described.add(new ProjectFileReplica.Described(op.getPath(),
                    tombstone ? FileKind.FILE : FileKind.valueOf(op.getType()), longOf(op.getVersion()), op.getSize(),
                    tombstone));
        }
        return ProjectFileReplica.summaryOf(described);
    }

    public static FilePayload header(String projectName, long incarnation) {
        final var payload = new FilePayload();
        payload.setProjectName(projectName);
        payload.setIncarnation(Long.toString(incarnation));
        return payload;
    }

    public static List<FileReplicationOp> listing(List<ProjectFileReplica.Described> described) {
        final var ops = new ArrayList<FileReplicationOp>(described.size());
        for (final var entry : described) {
            final var op = new FileReplicationOp();
            op.setType(entry.tombstone() ? TOMBSTONE : entry.kind().name());
            op.setPath(entry.path());
            op.setVersion(Long.toString(entry.version()));
            op.setSize(entry.size());
            ops.add(op);
        }
        return ops;
    }

    public static long longOf(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
