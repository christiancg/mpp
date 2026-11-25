package org.ccg.mpp.ops.req.validations;

import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.ProjectFilePath;
import org.ccg.mpp.ex.InvalidFilePathException;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.req.DeleteFileRequest;
import org.ccg.mpp.ops.req.ListFilesRequest;
import org.ccg.mpp.ops.req.MakeDirectoryRequest;
import org.ccg.mpp.ops.req.MoveFileRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.ReadFileRequest;
import org.ccg.mpp.ops.req.StatFileRequest;
import org.ccg.mpp.ops.req.WriteFileRequest;

public final class FileRequestValidator {
    private static final String ENCODING = "encoding";
    private static final String MODE = "mode";

    private FileRequestValidator() {
    }

    public static ValidationResult validate(OperationRequest request) {
        final var project = NameValidations.validateProjectNameOnly(request.getProjectName());
        if (!project.isValid()) {
            return project;
        }
        return switch (request) {
            case WriteFileRequest write -> validateWrite(write);
            case ReadFileRequest read -> validateRead(read);
            case StatFileRequest stat -> requirePath(stat.getPath(), "path");
            case ListFilesRequest list -> validateList(list);
            case DeleteFileRequest delete -> requirePath(delete.getPath(), "path");
            case MoveFileRequest move -> validateMove(move);
            case MakeDirectoryRequest makeDirectory -> requirePath(makeDirectory.getPath(), "path");
            default -> throw new IllegalArgumentException("Not a file operation: " + request.getType());
        };
    }

    private static ValidationResult validateWrite(WriteFileRequest request) {
        final var path = requirePath(request.getPath(), "path");
        if (!path.isValid()) {
            return path;
        }
        if (request.getContent() == null && request.getRawContent() == null) {
            return ValidationResult.fail(ErrorCode.FILE_CONTENT_INVALID,
                    "content is required; send \"\" for an empty" + " file");
        }
        final var option = validateOption(request.getUnrecognizedOption());
        if (!option.isValid()) {
            return option;
        }
        if (request.getIfVersion() != null && versionOf(request.getIfVersion()) == null) {
            return ValidationResult.fail("ifVersion must be a version a file operation answered, or \"0\"");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateRead(ReadFileRequest request) {
        final var path = requirePath(request.getPath(), "path");
        if (!path.isValid()) {
            return path;
        }
        if (request.getOffset() < 0) {
            return ValidationResult.fail("offset must not be negative");
        }
        if (request.getLength() != null && request.getLength() <= 0) {
            return ValidationResult.fail("length must be positive");
        }
        return validateOption(request.getUnrecognizedOption());
    }

    private static ValidationResult validateList(ListFilesRequest request) {
        if (request.getPath() != null) {
            final var path = requirePath(request.getPath(), "path");
            if (!path.isValid()) {
                return path;
            }
        }
        final var limit = request.getLimit();
        if (limit != null && (limit < 1 || limit > Globals.FILES_LIST_MAX_ENTRIES)) {
            return ValidationResult.fail("limit must be between 1 and " + Globals.FILES_LIST_MAX_ENTRIES);
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateMove(MoveFileRequest request) {
        final var from = requirePath(request.getFrom(), "from");
        return from.isValid() ? requirePath(request.getTo(), "to") : from;
    }

    private static ValidationResult validateOption(String unrecognized) {
        if (unrecognized == null) {
            return ValidationResult.ok();
        }
        return switch (unrecognized) {
            case ENCODING -> ValidationResult.fail(ErrorCode.FILE_CONTENT_INVALID, "encoding must be base64 or utf8");
            case MODE ->
                ValidationResult.fail(ErrorCode.FILE_CONTENT_INVALID, "mode must be REPLACE, CREATE or APPEND");
            default -> ValidationResult.fail(ErrorCode.FILE_CONTENT_INVALID, unrecognized + " is not recognised");
        };
    }

    private static ValidationResult requirePath(String raw, String field) {
        if (raw == null) {
            return ValidationResult.fail(ErrorCode.FILE_PATH_INVALID, field + " is required");
        }
        try {
            ProjectFilePath.parse(raw);
            return ValidationResult.ok();
        } catch (InvalidFilePathException e) {
            return ValidationResult.fail(ErrorCode.FILE_PATH_INVALID, e.getMessage());
        }
    }

    public static Long versionOf(String version) {
        try {
            final var parsed = Long.parseLong(version);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
