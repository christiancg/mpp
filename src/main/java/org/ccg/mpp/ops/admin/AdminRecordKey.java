package org.ccg.mpp.ops.admin;

import java.util.List;
import java.util.Locale;
import org.ccg.mpp.ops.req.DeleteEntrypointRequest;
import org.ccg.mpp.ops.req.DeleteMiddlewareRequest;
import org.ccg.mpp.ops.req.DeleteProcedureRequest;
import org.ccg.mpp.ops.req.DeleteScheduleRequest;
import org.ccg.mpp.ops.req.DeleteUserRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.PermissionsRequest;
import org.ccg.mpp.ops.req.SaveEntrypointRequest;
import org.ccg.mpp.ops.req.SaveMiddlewareRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveScheduleRequest;
import org.ccg.mpp.ops.req.SetPasswordRequest;

public record AdminRecordKey(Kind kind, String projectName, String name) {
    private static final String SEPARATOR = "|";

    public enum Kind {
        PROJECT, COLLECTION, USER, SCHEMA, TRIGGERS, PROCEDURE, SCHEDULE, ENTRYPOINT, MIDDLEWARE;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static AdminRecordKey project(String projectName) {
        return new AdminRecordKey(Kind.PROJECT, projectName, projectName);
    }

    public static AdminRecordKey collection(String projectName, String collName) {
        return new AdminRecordKey(Kind.COLLECTION, projectName, collName);
    }

    public static AdminRecordKey user(String username) {
        return new AdminRecordKey(Kind.USER, "", username);
    }

    public static AdminRecordKey schema(String projectName, String collName) {
        return new AdminRecordKey(Kind.SCHEMA, projectName, collName);
    }

    public static AdminRecordKey triggers(String projectName, String collName) {
        return new AdminRecordKey(Kind.TRIGGERS, projectName, collName);
    }

    public static AdminRecordKey procedure(String projectName, String name) {
        return new AdminRecordKey(Kind.PROCEDURE, projectName, name);
    }

    public static AdminRecordKey schedule(String projectName, String name) {
        return new AdminRecordKey(Kind.SCHEDULE, projectName, name);
    }

    public static AdminRecordKey entrypoint(String projectName, String name) {
        return new AdminRecordKey(Kind.ENTRYPOINT, projectName, name);
    }

    public static AdminRecordKey middleware(String projectName, String name) {
        return new AdminRecordKey(Kind.MIDDLEWARE, projectName, name);
    }

    public String id() {
        return kind.label() + SEPARATOR + projectName + SEPARATOR + name;
    }

    public static AdminRecordKey parse(String id) {
        final var parts = id.split("\\|", 3);
        return new AdminRecordKey(Kind.valueOf(parts[0].toUpperCase(Locale.ROOT)), parts[1], parts[2]);
    }

    public static List<AdminRecordKey> touchedBy(OperationRequest request) {
        final var projectName = request.getProjectName();
        return switch (request) {
            case SaveProcedureRequest save -> List.of(procedure(projectName, save.getName()));
            case DeleteProcedureRequest delete -> List.of(procedure(projectName, delete.getName()));
            case SaveScheduleRequest save -> List.of(schedule(projectName, save.getName()));
            case DeleteScheduleRequest delete -> List.of(schedule(projectName, delete.getName()));
            case SaveEntrypointRequest save -> save.getProcedure() == null
                    ? List.of(entrypoint(projectName, save.getName()))
                    : List.of(procedure(projectName, save.getProcedure().getName()),
                            entrypoint(projectName, save.getName()));
            case DeleteEntrypointRequest delete -> List.of(entrypoint(projectName, delete.getName()));
            case SaveMiddlewareRequest save -> save.getProcedure() == null
                    ? List.of(middleware(projectName, save.getName()))
                    : List.of(procedure(projectName, save.getProcedure().getName()),
                            middleware(projectName, save.getName()));
            case DeleteMiddlewareRequest delete -> List.of(middleware(projectName, delete.getName()));
            case PermissionsRequest permissions -> List.of(user(permissions.getUsername()));
            case DeleteUserRequest delete -> List.of(user(delete.getUsername()));
            case SetPasswordRequest password -> List.of(user(password.getUsername()));
            default -> touchedByType(request);
        };
    }

    private static List<AdminRecordKey> touchedByType(OperationRequest request) {
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        return switch (request.getType()) {
            case CREATE_PROJECT, DROP_PROJECT, SET_PROJECT_OWNERS -> List.of(project(projectName));
            case CREATE_COLLECTION, DROP_COLLECTION, CREATE_INDEX, DROP_INDEX ->
                List.of(collection(projectName, collName));
            case SAVE_SCHEMA, DELETE_SCHEMA -> List.of(schema(projectName, collName));
            case SAVE_TRIGGER, DELETE_TRIGGER -> List.of(triggers(projectName, collName));
            default -> List.of();
        };
    }
}
