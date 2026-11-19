package org.ccg.mpp.data;

import java.util.Objects;

public class IndexedDbEntry extends JsonDocumentEntry {
    private PkIndexEntry index;
    private long version;

    public DbEntry toDbEntry() {
        final var entry = new DbEntry();
        entry.set_id(_id);
        entry.setProjectName(projectName);
        entry.setCollectionName(collectionName);
        entry.setData(data);
        if (index != null) {
            entry.setPage(index.getPage());
            entry.rememberEncodedLength(Math.toIntExact(index.getLength()));
        }
        entry.setVersion(version);
        entry.setPreviousByteSize(previousByteSize);
        return entry;
    }

    public PkIndexEntry getIndex() {
        return index;
    }

    public void setIndex(PkIndexEntry index) {
        this.index = index;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof IndexedDbEntry that))
            return false;
        return previousByteSize == that.previousByteSize && Objects.equals(_id, that._id)
                && Objects.equals(projectName, that.projectName) && Objects.equals(collectionName, that.collectionName)
                && Objects.equals(data, that.data) && Objects.equals(index, that.index);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_id, projectName, collectionName, data, index, previousByteSize);
    }

    @Override
    public String toString() {
        return "IndexedDbEntry(id=" + _id + ", projectName=" + projectName + ", collectionName=" + collectionName
                + ", data=" + data + ", index=" + index + ", previousByteSize=" + previousByteSize + ")";
    }
}
