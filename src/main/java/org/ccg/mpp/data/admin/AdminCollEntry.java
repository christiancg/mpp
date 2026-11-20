package org.ccg.mpp.data.admin;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;

public class AdminCollEntry extends DbEntry {
    private static final String INDEXES_FIELD_NAME = "indexes";
    private static final String INCARNATION_FIELD_NAME = "incarnation";
    private Set<String> indexes;
    private long incarnation;

    private AdminCollEntry() {
        super.setProjectName(Globals.ADMIN_PROJECT_NAME);
        super.setCollectionName(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
    }

    public AdminCollEntry(String projectName, String collName) {
        this(projectName, collName, new HashSet<>());
    }

    public AdminCollEntry(String projectName, String collName, Set<String> indexes) {
        super.setProjectName(Globals.ADMIN_PROJECT_NAME);
        super.setCollectionName(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        this.set_id(Cache.getCollectionIdentifier(projectName, collName));
        this.indexes = indexes;
        final var json = new JsonObject();
        final var arr = new JsonArray();
        indexes.forEach(arr::add);
        json.add(INDEXES_FIELD_NAME, arr);
        json.addProperty(INCARNATION_FIELD_NAME, Long.toString(incarnation));
        this.setData(json);
    }

    public void setIncarnation(long incarnation) {
        this.incarnation = incarnation;
        final var data = getData();
        if (data != null) {
            data.addProperty(INCARNATION_FIELD_NAME, Long.toString(incarnation));
            this.setData(data);
        }
    }

    public long getIncarnation() {
        return incarnation;
    }

    public static AdminCollEntry fromJsonObject(JsonObject object) {
        final var result = new AdminCollEntry();
        result.setData(object);
        final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
        result.set_id(id);
        final var collections = object.get(INDEXES_FIELD_NAME).asJsonArray().asList().stream()
                .map(element -> element.asJsonString().getValue()).collect(Collectors.toSet());
        result.setIndexes(collections);
        result.incarnation = object.has(INCARNATION_FIELD_NAME)
                ? Long.parseLong(object.get(INCARNATION_FIELD_NAME).asJsonString().getValue())
                : 0L;
        result.setProjectName(Globals.ADMIN_PROJECT_NAME);
        result.setCollectionName(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        return result;
    }

    public void setIndexes(Set<String> indexes) {
        this.indexes = indexes;
        final var data = getData();
        final var arr = new JsonArray();
        indexes.forEach(arr::add);
        data.add(INDEXES_FIELD_NAME, arr);
        this.setData(data);
    }

    public Set<String> getIndexes() {
        return indexes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof AdminCollEntry that))
            return false;
        if (!super.equals(o))
            return false;
        return Objects.equals(indexes, that.indexes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), indexes);
    }

    @Override
    public String toString() {
        return "AdminCollEntry(super=" + super.toString() + ", indexes=" + indexes + ")";
    }
}
