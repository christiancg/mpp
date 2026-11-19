package org.ccg.mpp.data.admin;

import java.util.Objects;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;

public class AdminPageEntry extends DbEntry {
    public static String pagesCollectionName(String projectName, String collName) {
        return projectName + "_" + collName;
    }

    private static final String PAGE_FIELD_NAME = "page";
    private static final String ENTRY_COUNT_FIELD_NAME = "entryCount";
    private static final String PAGE_SIZE_FIELD_NAME = "size";
    private long page;
    private int entryCount;
    private long pageSize;
    private long storagePage;

    public AdminPageEntry(String projectName, String collectionName) {
        this(projectName, collectionName, 0L);
    }

    public AdminPageEntry(String projectName, String collectionName, long page) {
        setProjectName(Globals.ADMIN_PAGES_PROJECT_NAME);
        setCollectionName(pagesCollectionName(projectName, collectionName));
        this.page = page;
        set_id(buildId(projectName, collectionName, page));
        setData(new JsonObject());
        syncData();
    }

    public static AdminPageEntry fromJsonObject(String projectName, String collName, JsonObject object) {
        final var result = new AdminPageEntry(projectName, collName);
        result.setData(object);
        final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
        result.set_id(id);
        result.page = object.get(PAGE_FIELD_NAME).asJsonNumber().getValue().longValue();
        result.entryCount = object.get(ENTRY_COUNT_FIELD_NAME).asJsonNumber().getValue().intValue();
        result.pageSize = object.get(PAGE_SIZE_FIELD_NAME).asJsonNumber().getValue().longValue();
        result.syncData();
        return result;
    }

    public static String buildId(String projectName, String collName, long page) {
        return projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName + Globals.COLL_IDENTIFIER_SEPARATOR + page;
    }

    @Override
    public void setPage(long page) {
        this.page = page;
        syncData();
    }

    public void setEntryCount(int entryCount) {
        this.entryCount = entryCount;
        syncField(ENTRY_COUNT_FIELD_NAME, entryCount);
    }

    public void setPageSize(long pageSize) {
        this.pageSize = pageSize;
        syncField(PAGE_SIZE_FIELD_NAME, pageSize);
    }

    private void syncField(String field, double value) {
        final var data = getData();
        if (data == null) {
            return;
        }
        data.add(field, new JsonNumber(value));
        setData(data);
    }

    private void syncData() {
        final var data = getData();
        if (data == null) {
            return;
        }
        data.addProperty(PAGE_FIELD_NAME, page);
        data.addProperty(ENTRY_COUNT_FIELD_NAME, entryCount);
        data.addProperty(PAGE_SIZE_FIELD_NAME, pageSize);
        setData(data);
    }

    @Override
    public long getPage() {
        return page;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public long getStoragePage() {
        return storagePage;
    }

    public void setStoragePage(long storagePage) {
        this.storagePage = storagePage;
    }

    public long getPageSize() {
        return pageSize;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof AdminPageEntry that))
            return false;
        if (!super.equals(o))
            return false;
        return page == that.page && entryCount == that.entryCount && pageSize == that.pageSize;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), page, entryCount, pageSize);
    }

    @Override
    public String toString() {
        return "AdminPageEntry(super=" + super.toString() + ", page=" + page + ", entryCount=" + entryCount
                + ", pageSize=" + pageSize + ")";
    }
}
