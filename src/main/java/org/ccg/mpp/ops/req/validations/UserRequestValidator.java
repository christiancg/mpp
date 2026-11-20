package org.ccg.mpp.ops.req.validations;

import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.data.auth.ScriptPermissionLevel;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.req.AuthenticateRequest;
import org.ccg.mpp.ops.req.ChangePermissionsRequest;
import org.ccg.mpp.ops.req.CreateUserRequest;
import org.ccg.mpp.ops.req.DeleteUserRequest;
import org.ccg.mpp.ops.req.ListUsersRequest;
import org.ccg.mpp.ops.req.SetPasswordRequest;
import org.ccg.mpp.ops.req.SetProjectOwnersRequest;

public final class UserRequestValidator {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final int PASSWORD_MIN_LENGTH = Globals.PASSWORD_MIN_LENGTH;

    private UserRequestValidator() {
    }

    static ValidationResult validateAuthenticate(AuthenticateRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ValidationResult.fail("username is required");
        }
        if (!request.getUsername().matches(NameValidations.USERNAME_PATTERN)) {
            return ValidationResult.fail("username must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            return ValidationResult.fail("password is required");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateCreateUser(CreateUserRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ValidationResult.fail("username is required");
        }
        if (!request.getUsername().matches(NameValidations.USERNAME_PATTERN)) {
            return ValidationResult.fail("username must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        if (request.getPassword() == null || request.getPassword().length() < PASSWORD_MIN_LENGTH) {
            return ValidationResult.fail("password must be at least " + PASSWORD_MIN_LENGTH + " characters");
        }
        final var projectPermsResult = validateRawPermissionMaps(request.getRawProjectPermissions(),
                request.getRawCollectionPermissions());
        if (!projectPermsResult.isValid()) {
            return projectPermsResult;
        }
        return validateRawScriptPermissions(request.getRawScriptPermissions());
    }

    static ValidationResult validateDeleteUser(DeleteUserRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ValidationResult.fail("username is required");
        }
        if (!request.getUsername().matches(NameValidations.USERNAME_PATTERN)) {
            return ValidationResult.fail("username must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateChangePermissions(ChangePermissionsRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ValidationResult.fail("username is required");
        }
        if (!request.getUsername().matches(NameValidations.USERNAME_PATTERN)) {
            return ValidationResult.fail("username must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        final var projectPermsResult = validateRawPermissionMaps(request.getRawProjectPermissions(),
                request.getRawCollectionPermissions());
        if (!projectPermsResult.isValid()) {
            return projectPermsResult;
        }
        return validateRawScriptPermissions(request.getRawScriptPermissions());
    }

    static ValidationResult validateSetPassword(SetPasswordRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ValidationResult.fail("username is required");
        }
        if (!request.getUsername().matches(NameValidations.USERNAME_PATTERN)) {
            return ValidationResult.fail("username must be 3-64 alphanumeric characters, underscores, or hyphens");
        }
        if (request.getNewPassword() == null || request.getNewPassword().length() < PASSWORD_MIN_LENGTH) {
            return ValidationResult.fail("newPassword must be at least " + PASSWORD_MIN_LENGTH + " characters");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateListUsers(ListUsersRequest request) {
        return DataRequestValidator.validateAggregationSteps(request.getAggregationSteps());
    }

    static ValidationResult validateSetProjectOwners(SetProjectOwnersRequest request) {
        final var projectResult = NameValidations.validateProjectName(request.getProjectName(), true);
        if (!projectResult.isValid()) {
            return projectResult;
        }
        for (var owner : request.getOwners()) {
            if (!owner.matches(NameValidations.NAME_PATTERN)) {
                return ValidationResult
                        .fail("owner username must be 3-64 alphanumeric characters, underscores, or hyphens");
            }
            if (cache.getAdminUserEntry(owner) == null) {
                return ValidationResult.fail("user '" + owner + "' does not exist");
            }
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateRawScriptPermissions(JsonObject scriptPermissions) {
        if (scriptPermissions == null) {
            return ValidationResult.ok();
        }
        for (var entry : scriptPermissions.entrySet()) {
            final var projectName = entry.getKey();
            if (NameValidations.isReservedProjectName(projectName)) {
                return ValidationResult.fail("projectName '" + projectName + "' is reserved");
            }
            if (!projectName.matches(NameValidations.NAME_PATTERN)) {
                return ValidationResult.fail(
                        "project name in script permissions must be 3-64 alphanumeric characters, underscores, or hyphens");
            }
            // The boolean form is still accepted for backward compatibility; a typo'd level name must fail
            // loudly rather than read as NONE.
            final var value = entry.getValue();
            if (value == null || value.getJsonType() == JsonBaseElement.JsonType.BOOLEAN) {
                continue;
            }
            if (value.getJsonType() != JsonBaseElement.JsonType.STRING
                    || !ScriptPermissionLevel.isValidName(value.asJsonString().getValue())) {
                return ValidationResult.fail("script permission for project '" + projectName
                        + "' must be one of NONE, RUN, MANAGE (or the legacy true/false)");
            }
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateRawPermissionMaps(JsonObject projectPermissions, JsonObject collectionPermissions) {
        if (projectPermissions != null) {
            for (var entry : projectPermissions.entrySet()) {
                final var projectName = entry.getKey();
                if (NameValidations.isReservedProjectName(projectName)) {
                    return ValidationResult.fail("projectName '" + projectName + "' is reserved");
                }
                if (!projectName.matches(NameValidations.NAME_PATTERN)) {
                    return ValidationResult.fail(
                            "project name in permissions must be 3-64 alphanumeric characters, underscores, or hyphens");
                }
                try {
                    PermissionLevel.valueOf(entry.getValue().asJsonString().getValue());
                } catch (Exception e) {
                    return ValidationResult.fail("invalid permission level for project '" + projectName + "'");
                }
            }
        }
        if (collectionPermissions != null) {
            for (var entry : collectionPermissions.entrySet()) {
                final var collKey = entry.getKey();
                final var parts = collKey.split("\\|", -1);
                if (parts.length != 2) {
                    return ValidationResult.fail("collection permission key must be in format 'project|collection'");
                }
                final var projectName = parts[0];
                final var collName = parts[1];
                if (NameValidations.isReservedProjectName(projectName)) {
                    return ValidationResult.fail("projectName '" + projectName + "' is reserved");
                }
                if (!projectName.matches(NameValidations.NAME_PATTERN)) {
                    return ValidationResult.fail(
                            "project name in collection permission must be 3-64 alphanumeric characters, underscores, or hyphens");
                }
                if (!collName.matches(NameValidations.NAME_PATTERN)) {
                    return ValidationResult.fail(
                            "collection name in permission must be 3-64 alphanumeric characters, underscores, or hyphens");
                }
                try {
                    PermissionLevel.valueOf(entry.getValue().asJsonString().getValue());
                } catch (Exception e) {
                    return ValidationResult.fail("invalid permission level for collection '" + collKey + "'");
                }
            }
        }
        return ValidationResult.ok();
    }
}
