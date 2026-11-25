package org.ccg.mpp.ops.files;

import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.HybridClock;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.FileEncoding;
import org.ccg.mpp.data.FileEntry;
import org.ccg.mpp.data.FileWriteMode;
import org.ccg.mpp.data.ProjectFilePath;
import org.ccg.mpp.ex.FileApplyPendingException;
import org.ccg.mpp.ex.InvalidFilePathException;
import org.ccg.mpp.ex.ProjectFileException;
import org.ccg.mpp.fs.FileChange;
import org.ccg.mpp.fs.ProjectFileStore;
import org.ccg.mpp.fs.ProjectFileWrites;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationLocks;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.DeleteFileRequest;
import org.ccg.mpp.ops.req.ListFilesRequest;
import org.ccg.mpp.ops.req.MakeDirectoryRequest;
import org.ccg.mpp.ops.req.MoveFileRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.ReadFileRequest;
import org.ccg.mpp.ops.req.StatFileRequest;
import org.ccg.mpp.ops.req.WriteFileRequest;
import org.ccg.mpp.ops.req.validations.FileRequestValidator;
import org.ccg.mpp.ops.resp.DeleteFileResponse;
import org.ccg.mpp.ops.resp.FileInfo;
import org.ccg.mpp.ops.resp.ListFilesResponse;
import org.ccg.mpp.ops.resp.MakeDirectoryResponse;
import org.ccg.mpp.ops.resp.MoveFileResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.ReadFileResponse;
import org.ccg.mpp.ops.resp.StatFileResponse;
import org.ccg.mpp.ops.resp.WriteFileResponse;

public final class FileOperationHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ProjectFileStore store = IocContainer.get(ProjectFileStore.class);
    private static final HybridClock hybridClock = IocContainer.get(HybridClock.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(FileOperationHelper.class);

    private FileOperationHelper() {
    }

    @FunctionalInterface
    private interface FileAttempt {
        OperationResponse run() throws Exception;
    }

    @FunctionalInterface
    private interface FileWrite {
        List<FileChange> apply(ProjectFileWrites writes, long version) throws Exception;
    }

    public static OperationResponse process(OperationRequest request) {
        final var type = request.getType();
        final var projectName = request.getProjectName();
        return OperationLocks.withProjectShared(projectName, type, ErrorCode.ERROR_ACCESSING_FILES, () -> {
            if (cache.getAdminProjectEntry(projectName) == null) {
                return new OperationResponse(type, ErrorCode.PROJECT_NOT_FOUND);
            }
            return mapped(type, () -> dispatch(request));
        });
    }

    private static OperationResponse dispatch(OperationRequest request) throws Exception {
        return switch (request) {
            case ReadFileRequest read -> read(read);
            case StatFileRequest stat -> stat(stat);
            case ListFilesRequest list -> list(list);
            case WriteFileRequest write -> write(write);
            case MakeDirectoryRequest makeDirectory -> makeDirectory(makeDirectory);
            case DeleteFileRequest delete -> delete(delete);
            case MoveFileRequest move -> move(move);
            default -> throw new IllegalArgumentException("Not a file operation: " + request.getType());
        };
    }

    private static OperationResponse mapped(OperationType type, FileAttempt attempt) throws Exception {
        try {
            return attempt.run();
        } catch (ProjectFileException e) {
            return FileErrors.of(type, e);
        } catch (FileContentCodec.InvalidFileContentException e) {
            return new OperationResponse(type, ErrorCode.FILE_CONTENT_INVALID, e.getMessage());
        } catch (InvalidFilePathException e) {
            return new OperationResponse(type, ErrorCode.FILE_PATH_INVALID, e.getMessage());
        } catch (FileApplyPendingException e) {
            logger.error(type + " failed with " + ErrorCode.FILE_APPLY_PENDING.getCode(), e);
            return new OperationResponse(type, ErrorCode.FILE_APPLY_PENDING);
        }
    }

    private static OperationResponse read(ReadFileRequest request) throws Exception {
        final var path = ProjectFilePath.parse(request.getPath());
        final var maxRead = (int) Math.min(configuration.sizeValue(ConfigKey.FILES_MAX_READ_BYTES), Integer.MAX_VALUE);
        final var length = request.getLength() == null ? maxRead : (int) Math.min(request.getLength(), maxRead);
        final var slice = store.read(request.getProjectName(), path, request.getOffset(), length);
        final var encoding = FileEncoding.orDefault(request.getEncoding());
        return new ReadFileResponse(path.toString(), versionText(slice.entry().version()), slice.entry().size(),
                slice.offset(), FileContentCodec.encode(slice.bytes(), encoding), encoding, slice.reachesEnd());
    }

    private static OperationResponse stat(StatFileRequest request) throws Exception {
        final var path = ProjectFilePath.parse(request.getPath());
        final var entry = store.stat(request.getProjectName(), path);
        if (entry == null) {
            return new OperationResponse(OperationType.STAT_FILE, ErrorCode.FILE_NOT_FOUND, path.toString());
        }
        return new StatFileResponse(infoOf(path.toString(), entry));
    }

    private static OperationResponse list(ListFilesRequest request) throws Exception {
        final var folder = request.getPath() == null
                ? ProjectFilePath.root()
                : ProjectFilePath.parse(request.getPath());
        final var limit = request.getLimit() == null ? Globals.FILES_LIST_MAX_ENTRIES : request.getLimit();
        final var listing = store.list(request.getProjectName(), folder, request.isRecursive(), request.getAfter(),
                limit);
        final var entries = listing.entries().stream().map(listed -> infoOf(listed.path(), listed.entry())).toList();
        return new ListFilesResponse(entries, listing.next());
    }

    private static OperationResponse write(WriteFileRequest request) throws Exception {
        final var path = ProjectFilePath.parse(request.getPath());
        final var content = request.getRawContent() != null
                ? request.getRawContent()
                : FileContentCodec.decode(request.getContent(), request.getEncoding());
        final var spec = new ProjectFileWrites.WriteSpec(
                request.getMode() == null ? FileWriteMode.REPLACE : request.getMode(),
                request.getIfVersion() == null ? null : FileRequestValidator.versionOf(request.getIfVersion()),
                request.getCreateParents() == null || request.getCreateParents());
        return changing(request, content, (writes, version) -> writes.write(path, content, spec, version), changes -> {
            final var written = changes.getLast();
            return new WriteFileResponse(written.path(), versionText(written.version()), written.size());
        });
    }

    private static OperationResponse makeDirectory(MakeDirectoryRequest request) throws Exception {
        final var path = ProjectFilePath.parse(request.getPath());
        final var recursive = request.getRecursive() == null || request.getRecursive();
        return changing(request, null, (writes, version) -> writes.makeDirectory(path, recursive, version), changes -> {
            final var version = changes.isEmpty()
                    ? versionOf(store.stat(request.getProjectName(), path))
                    : changes.getLast().version();
            return new MakeDirectoryResponse(path.toString(), versionText(version), changes.size());
        });
    }

    private static OperationResponse delete(DeleteFileRequest request) throws Exception {
        final var path = ProjectFilePath.parse(request.getPath());
        return changing(request, null, (writes, version) -> writes.delete(path, request.isRecursive(), version),
                changes -> new DeleteFileResponse(changes.size()));
    }

    private static OperationResponse move(MoveFileRequest request) throws Exception {
        final var from = ProjectFilePath.parse(request.getFrom());
        final var to = ProjectFilePath.parse(request.getTo());
        final var createParents = request.getCreateParents() == null || request.getCreateParents();
        return changing(request, null,
                (writes, version) -> writes.move(from, to, request.isOverwrite(), createParents, version), changes -> {
                    final var version = changes.isEmpty()
                            ? versionOf(store.stat(request.getProjectName(), to))
                            : changes.getLast().version();
                    final var moved = changes.isEmpty() ? 0 : (int) changes.getLast().size();
                    return new MoveFileResponse(to.toString(), versionText(version), moved);
                });
    }

    @FunctionalInterface
    private interface Answer {
        OperationResponse of(List<FileChange> changes) throws Exception;
    }

    private static OperationResponse changing(OperationRequest request, byte[] content, FileWrite change,
            Answer answer) {
        final var type = request.getType();
        final var projectName = request.getProjectName();
        return OperationLocks.withCollectionLock(projectName, Globals.FILES_LOCK_NAME, type,
                ErrorCode.ERROR_ACCESSING_FILES, () -> mapped(type, () -> {
                    final var refusal = FileReplication.refusal(type, projectName);
                    if (refusal != null) {
                        return refusal;
                    }
                    final var changes = change.apply(store.writes(projectName), hybridClock.next());
                    final var unreplicated = FileReplication.replicate(type, projectName, changes, content);
                    return unreplicated != null ? unreplicated : answer.of(changes);
                }));
    }

    static FileInfo infoOf(String path, FileEntry entry) {
        return new FileInfo(path, entry.kind().name(), entry.size(), versionText(entry.version()),
                HybridClock.physicalOf(entry.version()));
    }

    private static long versionOf(FileEntry entry) {
        return entry == null ? 0L : entry.version();
    }

    static String versionText(long version) {
        return Long.toString(version);
    }
}
