package org.ccg.mpp.listen;

@FunctionalInterface
public interface DirtySink {
    void markDirty(String dbName, String collName);
}
