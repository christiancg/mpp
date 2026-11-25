package org.ccg.mpp.ops.files;

import org.ccg.mpp.ex.ProjectFileException;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.resp.OperationResponse;

final class FileErrors {
    private FileErrors() {
    }

    static OperationResponse of(OperationType type, ProjectFileException failure) {
        return new OperationResponse(type, codeOf(failure.reason()), failure.getMessage());
    }

    static ErrorCode codeOf(ProjectFileException.Reason reason) {
        return switch (reason) {
            case NOT_FOUND -> ErrorCode.FILE_NOT_FOUND;
            case EXISTS -> ErrorCode.FILE_EXISTS;
            case KIND_MISMATCH -> ErrorCode.FILE_KIND_MISMATCH;
            case NOT_EMPTY -> ErrorCode.DIRECTORY_NOT_EMPTY;
            case CASE_COLLISION -> ErrorCode.NAME_COLLIDES_ON_DISK;
            case VERSION_CONFLICT -> ErrorCode.FILE_VERSION_CONFLICT;
            case TOO_LARGE -> ErrorCode.FILE_TOO_LARGE;
            case QUOTA_EXCEEDED -> ErrorCode.FILE_QUOTA_EXCEEDED;
            case INVALID_MOVE -> ErrorCode.FILE_PATH_INVALID;
        };
    }
}
