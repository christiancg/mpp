package org.ccg.mpp.data.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;

public class AdminProjectEntry extends DbEntry {
    private static final String COLLECTIONS_FIELD_NAME = "collections";
    private static final String OWNERS_FIELD_NAME = "owners";
    private static final String INCARNATION_FIELD_NAME = "incarnation";
    private List<String> collections;
    private List<String> owners;
    private long incarnation;

    private AdminProjectEntry() {
        super.setProjectName(Globals.ADMIN_PROJECT_NAME);
        super.setCollectionName(Globals.ADMIN_PROJECTS_COLLECTION_NAME);
    }

    public AdminProjectEntry(String projectName) {
        this(projectName, new ArrayList<>(), new ArrayList<>());
    }

    public AdminProjectEntry(String projectName, List<String> collections) {
        this(projectName, collections, new ArrayList<>());
    }

    public AdminProjectEntry(String projectName, List<String> collections, List<String> owners) {
        this(projectName, collections, owners, 0L);
    }

    public AdminProjectEntry(String projectName, List<String> collections, List<String> owners, long incarnation) {
        super.setProjectName(Globals.ADMIN_PROJECT_NAME);
        super.setCollectionName(Globals.ADMIN_PROJECTS_COLLECTION_NAME);
        this.set_id(projectName);
        this.collections = collections;
        this.owners = owners;
        this.incarnation = incarnation;
        rebuildData();
    }

    public static AdminProjectEntry fromJsonObject(JsonObject object) {
        final var result = new AdminProjectEntry();
        result.setData(object);
        final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
        result.set_id(id);
        final var collectionsEl = object.get(COLLECTIONS_FIELD_NAME);
        result.collections = collectionsEl != null && !collectionsEl.isJsonNull()
                ? collectionsEl.asJsonArray().asList().stream().map(element -> element.asJsonString().getValue())
                        .collect(Collectors.toList())
                : new ArrayList<>();
        final var ownersEl = object.get(OWNERS_FIELD_NAME);
        result.owners = ownersEl != null && !ownersEl.isJsonNull()
                ? ownersEl.asJsonArray().asList().stream().map(element -> element.asJsonString().getValue())
                        .collect(Collectors.toList())
                : new ArrayList<>();
        final var incarnationEl = object.get(INCARNATION_FIELD_NAME);
        result.incarnation = incarnationEl != null && incarnationEl.isJsonString()
                ? Long.parseLong(incarnationEl.asJsonString().getValue())
                : 0L;
        result.setProjectName(Globals.ADMIN_PROJECT_NAME);
        result.setCollectionName(Globals.ADMIN_PROJECTS_COLLECTION_NAME);
        return result;
    }

    private void rebuildData() {
        final var data = new JsonObject();
        if (get_id() != null) {
            data.add(Globals.PK_FIELD, new JsonString(get_id()));
        }
        final var collectionsArr = new JsonArray();
        collections.forEach(collectionsArr::add);
        data.add(COLLECTIONS_FIELD_NAME, collectionsArr);
        final var ownersArr = new JsonArray();
        owners.forEach(ownersArr::add);
        data.add(OWNERS_FIELD_NAME, ownersArr);
        if (incarnation != 0L) {
            data.add(INCARNATION_FIELD_NAME, new JsonString(Long.toString(incarnation)));
        }
        this.setData(data);
    }

    public void setCollections(List<String> collections) {
        this.collections = collections;
        rebuildData();
    }

    public List<String> getCollections() {
        return collections;
    }

    public void setOwners(List<String> owners) {
        this.owners = owners;
        rebuildData();
    }

    public List<String> getOwners() {
        return owners != null ? owners : new ArrayList<>();
    }

    public long getIncarnation() {
        return incarnation;
    }

    public boolean isOwner(String username) {
        return owners != null && owners.contains(username);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof AdminProjectEntry that))
            return false;
        if (!super.equals(o))
            return false;
        return Objects.equals(collections, that.collections) && Objects.equals(owners, that.owners)
                && incarnation == that.incarnation;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), collections, owners, incarnation);
    }

    @Override
    public String toString() {
        return "AdminProjectEntry(super=" + super.toString() + ", collections=" + collections + ", owners=" + owners
                + ", incarnation=" + incarnation + ")";
    }
}
