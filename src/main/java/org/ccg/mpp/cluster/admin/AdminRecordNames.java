package org.ccg.mpp.cluster.admin;

import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.req.validations.NameValidations;

public final class AdminRecordNames {
    private AdminRecordNames() {
    }

    public static AdminRecordKey parse(String id) {
        if (id == null) {
            return null;
        }
        final AdminRecordKey key;
        try {
            key = AdminRecordKey.parse(id);
        } catch (RuntimeException malformed) {
            return null;
        }
        return key.id().equals(id) && accepts(key) ? key : null;
    }

    public static boolean accepts(AdminRecordKey key) {
        return switch (key.kind()) {
            case PROJECT -> isProject(key.projectName()) && key.projectName().equals(key.name());
            case USER -> key.projectName().isEmpty() && NameValidations.isUsername(key.name());
            case COLLECTION, SCHEMA, TRIGGERS, PROCEDURE, SCHEDULE ->
                isProject(key.projectName()) && NameValidations.isName(key.name());
        };
    }

    private static boolean isProject(String projectName) {
        return NameValidations.isName(projectName) && !NameValidations.isReservedProjectName(projectName);
    }
}
