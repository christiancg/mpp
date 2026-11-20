package org.ccg.mpp.cache;

public record AdmissionRequest(AccessKind tier, String projectName, String collName, String fieldName, long bytes) {
    static AdmissionRequest forFieldIndex(String projectName, String collName, String indexIdentifier, long bytes) {
        return new AdmissionRequest(AccessKind.FIELD_INDEX, projectName, collName,
                CacheableResource.indexedFieldOf(indexIdentifier), bytes);
    }

    boolean isFieldOf(String otherProject, String otherColl, String otherField) {
        return otherField != null && otherField.equals(fieldName) && otherColl.equals(collName)
                && otherProject.equals(projectName);
    }
}
