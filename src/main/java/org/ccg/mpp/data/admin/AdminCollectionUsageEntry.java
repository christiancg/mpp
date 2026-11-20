package org.ccg.mpp.data.admin;

import java.util.Objects;
import org.ccg.mpp.cache.AccessKind;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.ejson.elements.JsonObject;

public class AdminCollectionUsageEntry extends DbEntry {
    private static final String KIND_FIELD = "kind";
    private static final String PROJECT_NAME_FIELD = "projectName";
    private static final String COLL_NAME_FIELD = "collName";
    private static final String INDEX_KEY_FIELD = "indexKey";
    private static final String ACCESS_COUNT_FIELD = "accessCount";
    private static final String LAST_ACCESS_FIELD = "lastAccessMillis";

    private AccessKind kind;
    private String targetProject;
    private String collName;
    private String indexKey;
    private long accessCount;
    private long lastAccessMillis;

    private AdminCollectionUsageEntry() {
        setProjectName(Globals.ADMIN_PROJECT_NAME);
        setCollectionName(Globals.ADMIN_COLLECTION_USAGE_NAME);
        setData(new JsonObject());
    }

    public AdminCollectionUsageEntry(AccessKind kind, String projectName, String collName, String indexKey,
            long accessCount, long lastAccessMillis) {
        setProjectName(Globals.ADMIN_PROJECT_NAME);
        setCollectionName(Globals.ADMIN_COLLECTION_USAGE_NAME);
        this.kind = kind;
        this.targetProject = projectName;
        this.collName = collName;
        this.indexKey = indexKey == null ? "" : indexKey;
        this.accessCount = accessCount;
        this.lastAccessMillis = lastAccessMillis;
        set_id(buildId(projectName, collName, this.indexKey));
        setData(new JsonObject());
        syncData();
    }

    public static String buildId(String projectName, String collName, String indexKey) {
        final var key = indexKey == null ? "" : indexKey;
        return projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName + Globals.COLL_IDENTIFIER_SEPARATOR + key;
    }

    public static AdminCollectionUsageEntry fromJsonObject(JsonObject object) {
        final var result = new AdminCollectionUsageEntry();
        result.setData(object);
        result.set_id(object.get(Globals.PK_FIELD).asJsonString().getValue());
        result.kind = AccessKind.valueOf(object.get(KIND_FIELD).asJsonString().getValue());
        result.targetProject = object.get(PROJECT_NAME_FIELD).asJsonString().getValue();
        result.collName = object.get(COLL_NAME_FIELD).asJsonString().getValue();
        result.indexKey = object.get(INDEX_KEY_FIELD).asJsonString().getValue();
        result.accessCount = Long.parseLong(object.get(ACCESS_COUNT_FIELD).asJsonString().getValue());
        result.lastAccessMillis = Long.parseLong(object.get(LAST_ACCESS_FIELD).asJsonString().getValue());
        return result;
    }

    private void syncData() {
        final var data = getData();
        if (data == null) {
            return;
        }
        data.addProperty(KIND_FIELD, kind.name());
        data.addProperty(PROJECT_NAME_FIELD, targetProject);
        data.addProperty(COLL_NAME_FIELD, collName);
        data.addProperty(INDEX_KEY_FIELD, indexKey);
        data.addProperty(ACCESS_COUNT_FIELD, Long.toString(accessCount));
        data.addProperty(LAST_ACCESS_FIELD, Long.toString(lastAccessMillis));
        setData(data);
    }

    public AccessKind getKind() {
        return kind;
    }

    public String getTargetProject() {
        return targetProject;
    }

    public String getCollName() {
        return collName;
    }

    public String getIndexKey() {
        return indexKey;
    }

    public long getAccessCount() {
        return accessCount;
    }

    public void setAccessCount(long accessCount) {
        this.accessCount = accessCount;
        syncData();
    }

    public long getLastAccessMillis() {
        return lastAccessMillis;
    }

    public void setLastAccessMillis(long lastAccessMillis) {
        this.lastAccessMillis = lastAccessMillis;
        syncData();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof AdminCollectionUsageEntry that))
            return false;
        if (!super.equals(o))
            return false;
        return accessCount == that.accessCount && lastAccessMillis == that.lastAccessMillis && kind == that.kind
                && Objects.equals(targetProject, that.targetProject) && Objects.equals(collName, that.collName)
                && Objects.equals(indexKey, that.indexKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), kind, targetProject, collName, indexKey, accessCount, lastAccessMillis);
    }

    @Override
    public String toString() {
        return "AdminCollectionUsageEntry(super=" + super.toString() + ", kind=" + kind + ", targetProject="
                + targetProject + ", collName=" + collName + ", indexKey=" + indexKey + ", accessCount=" + accessCount
                + ", lastAccessMillis=" + lastAccessMillis + ")";
    }
}
