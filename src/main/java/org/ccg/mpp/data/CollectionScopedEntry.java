package org.ccg.mpp.data;

public abstract class CollectionScopedEntry {
    protected String projectName;
    protected String collectionName;

    protected CollectionScopedEntry() {
    }

    protected CollectionScopedEntry(String projectName, String collectionName) {
        this.projectName = projectName;
        this.collectionName = collectionName;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }
}
